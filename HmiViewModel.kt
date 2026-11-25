package com.example.s7hmi

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class HmiViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("s7hmi", Context.MODE_PRIVATE)
    private val dir: File = app.filesDir
    private val client = S7Client()
    private val writer = Executors.newSingleThreadExecutor() // scritture in ordine (premi/rilascia)
    private var job: Job? = null

    var config by mutableStateOf(loadConfig())
        private set
    val pages = mutableStateListOf<Page>().apply { addAll(loadPages()) }
    var currentPageId by mutableStateOf(pages.first().id)
        private set
    val tags = mutableStateListOf<Tag>().apply { addAll(loadTags()) }
    val values = mutableStateMapOf<String, Any>() // Boolean oppure Double
    var status by mutableStateOf("Disconnesso")
        private set
    var connected by mutableStateOf(false)
        private set
    var editMode by mutableStateOf(false)
    var showTabs by mutableStateOf(prefs.getBoolean("showTabs", true))
        private set
    var background by mutableStateOf<ImageBitmap?>(loadBackground(currentPageId))
        private set

    private fun bgFile(pageId: String) = File(dir, "bg_$pageId.jpg")

    // ---- comunicazione (si leggono solo gli oggetti della pagina visibile) ----
    fun startPolling() {
        if (job?.isActive == true) return
        job = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (!client.isConnected) {
                        status = "Connessione a ${config.ip}…"
                        client.connect(config.ip, config.rack, config.slot)
                    }
                    val page = currentPageId
                    var bad = 0
                    for (t in tags.toList()) {
                        if (!t.widget.hasAddress || t.pageId != page) continue
                        try {
                            values[t.id] = readTag(t)
                        } catch (e: S7ItemException) {
                            values.remove(t.id); bad++
                        }
                    }
                    connected = true
                    status = if (bad > 0) "Connesso · $bad oggetti non leggibili" else "Connesso"
                    delay(300)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    connected = false
                    status = "Errore: ${e.message ?: e.javaClass.simpleName}"
                    values.clear()
                    client.close()
                    delay(2000)
                }
            }
        }
    }

    fun stopPolling() {
        job?.cancel()
        connected = false
        status = "Disconnesso"
        viewModelScope.launch(Dispatchers.IO) { client.close() }
    }

    private fun readTag(t: Tag): Any {
        val raw = client.readBytes(t.db, t.offset, t.type.bytes)
        return if (t.type == DataType.BOOL) ((raw[0].toInt() shr t.bit) and 1) == 1
        else decodeNumber(t.type, raw)
    }

    fun writeBool(t: Tag, v: Boolean) {
        writer.execute {
            try { client.writeBit(t.db, t.offset, t.bit, v); values[t.id] = v }
            catch (e: Exception) { status = "Scrittura fallita: ${e.message}" }
        }
    }

    fun writeNumber(t: Tag, v: Double) {
        writer.execute {
            try { client.writeBytes(t.db, t.offset, encodeNumber(t.type, v)); values[t.id] = v }
            catch (e: Exception) { status = "Scrittura fallita: ${e.message}" }
        }
    }

    // ---- configurazione ----
    fun saveConfig(c: PlcConfig) {
        config = c
        prefs.edit().putString("ip", c.ip).putInt("rack", c.rack).putInt("slot", c.slot).apply()
        viewModelScope.launch(Dispatchers.IO) { client.close() } // il ciclo si riconnette col nuovo IP
    }

    fun setShowTabs(v: Boolean) {
        showTabs = v
        prefs.edit().putBoolean("showTabs", v).apply()
    }

    // ---- pagine ----
    fun selectPage(id: String) {
        if (pages.none { it.id == id }) return
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = loadBackground(id)
            values.clear() // i valori della nuova pagina arrivano al ciclo successivo
            background = bmp
            currentPageId = id
        }
    }

    /** target: id di una pagina, oppure "__next" / "__prev" (circolare). */
    fun navigate(target: String) {
        if (pages.isEmpty()) return
        val i = pages.indexOfFirst { it.id == currentPageId }.coerceAtLeast(0)
        val dest = when (target) {
            "__next" -> pages[(i + 1) % pages.size].id
            "__prev" -> pages[(i - 1 + pages.size) % pages.size].id
            else -> target
        }
        selectPage(dest)
    }

    fun addPage(name: String) {
        val p = Page(name = name.ifBlank { "Pagina" })
        pages.add(p)
        savePages()
        selectPage(p.id)
    }

    fun renamePage(id: String, name: String) {
        val i = pages.indexOfFirst { it.id == id }
        if (i < 0) return
        pages[i] = pages[i].copy(name = name.ifBlank { pages[i].name })
        savePages()
    }

    fun movePage(id: String, delta: Int) {
        val i = pages.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in pages.indices) return
        val p = pages.removeAt(i)
        pages.add(j, p)
        savePages()
    }

    fun deletePage(id: String) {
        if (pages.size <= 1) return
        val idx = pages.indexOfFirst { it.id == id }
        if (idx < 0) return
        pages.removeAt(idx)
        tags.removeAll { it.pageId == id }
        savePages(); saveTags()
        viewModelScope.launch(Dispatchers.IO) { bgFile(id).delete() }
        if (currentPageId == id) selectPage(pages[idx.coerceAtMost(pages.lastIndex)].id)
    }

    // ---- progettazione degli oggetti ----
    fun upsertTag(t: Tag) {
        val tt = if (t.pageId.isEmpty()) t.copy(pageId = currentPageId) else t
        val i = tags.indexOfFirst { it.id == tt.id }
        if (i >= 0) tags[i] = tt else tags.add(tt)
        saveTags()
    }

    fun removeTag(t: Tag) {
        tags.removeAll { it.id == t.id }
        values.remove(t.id)
        saveTags()
    }

    fun duplicateTag(t: Tag) {
        upsertTag(t.copy(
            id = UUID.randomUUID().toString(),
            x = (t.x + 0.03f).coerceAtMost(1f - t.w),
            y = (t.y + 0.04f).coerceAtMost(1f - t.h)
        ))
    }

    fun moveTag(id: String, dx: Float, dy: Float) {
        val i = tags.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = tags[i]
        tags[i] = t.copy(x = (t.x + dx).coerceIn(0f, 1f - t.w), y = (t.y + dy).coerceIn(0f, 1f - t.h))
    }

    fun resizeTag(id: String, dw: Float, dh: Float) {
        val i = tags.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = tags[i]
        tags[i] = t.copy(w = (t.w + dw).coerceIn(0.04f, 1f - t.x), h = (t.h + dh).coerceIn(0.05f, 1f - t.y))
    }

    /** A fine trascinamento: aggancia alla griglia (80 x 50) e salva. */
    fun commitLayout() {
        fun snap(v: Float, n: Int) = Math.round(v * n) / n.toFloat()
        for (i in tags.indices) {
            val t = tags[i]
            val w = snap(t.w, 80).coerceIn(0.04f, 1f)
            val h = snap(t.h, 50).coerceIn(0.05f, 1f)
            tags[i] = t.copy(
                w = w, h = h,
                x = snap(t.x, 80).coerceIn(0f, 1f - w),
                y = snap(t.y, 50).coerceIn(0f, 1f - h)
            )
        }
        saveTags()
    }

    // ---- sfondo della pagina corrente ----
    fun setBackground(uri: Uri) {
        val pid = currentPageId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var s = 1
                while (bounds.outWidth / s > 1920 || bounds.outHeight / s > 1920) s *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = s }
                val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp == null) { status = "Immagine non leggibile"; return@launch }
                bgFile(pid).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (currentPageId == pid) background = bmp.asImageBitmap()
            } catch (e: Exception) {
                status = "Sfondo non caricato: ${e.message}"
            }
        }
    }

    fun removeBackground() {
        val pid = currentPageId
        background = null
        viewModelScope.launch(Dispatchers.IO) { bgFile(pid).delete() }
    }

    // ---- persistenza ----
    private fun saveTags() {
        prefs.edit().putString("tags", JSONArray(tags.map { it.toJson() }).toString()).apply()
    }

    private fun savePages() {
        prefs.edit().putString("pages", JSONArray(pages.map { it.toJson() }).toString()).apply()
    }

    private fun loadConfig() = PlcConfig(
        prefs.getString("ip", "192.168.0.1") ?: "192.168.0.1",
        prefs.getInt("rack", 0), prefs.getInt("slot", 1)
    )

    private fun loadPages(): List<Page> {
        val list = try {
            val a = JSONArray(prefs.getString("pages", "[]"))
            List(a.length()) { val o = a.getJSONObject(it); Page(o.getString("id"), o.optString("name", "Pagina")) }
        } catch (e: Exception) { emptyList() }
        if (list.isNotEmpty()) return list
        val p = Page(name = "Pagina 1")
        prefs.edit().putString("pages", JSONArray(listOf(p.toJson())).toString()).apply()
        val old = File(dir, "background.jpg") // sfondo unico delle versioni precedenti
        if (old.exists()) old.renameTo(bgFile(p.id))
        return listOf(p)
    }

    private fun loadTags(): List<Tag> = try {
        val a = JSONArray(prefs.getString("tags", "[]"))
        List(a.length()) { tagFromJson(a.getJSONObject(it)) }
            .map { t -> if (pages.none { it.id == t.pageId }) t.copy(pageId = pages.first().id) else t }
    } catch (e: Exception) { emptyList() }

    private fun loadBackground(pageId: String): ImageBitmap? {
        val f = bgFile(pageId)
        return if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
    }

    override fun onCleared() {
        job?.cancel()
        Thread { client.close() }.start()
        writer.shutdown()
    }
}
