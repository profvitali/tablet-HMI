package com.example.s7hmi

import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

enum class DataType(val bytes: Int) { BOOL(1), BYTE(1), INT(2), DINT(4), REAL(4) }

enum class WidgetType(val label: String, val isBool: Boolean, val hasAddress: Boolean = true) {
    BUTTON_MOMENTARY("Pulsante monostabile", true),
    BUTTON_LATCH("Pulsante bistabile", true),
    VALUE_OUT("Visualizza valore", false),
    VALUE_IN("Immetti valore", false),
    LAMP("Spia", true),
    LEVEL("Barra livello", false),
    ALARM("Allarme", true),
    BUTTON_NAV("Vai a pagina", false, false),
    LABEL("Testo", false, false)
}

/** Dimensione iniziale (frazione della tela) per tipo di oggetto. */
fun defaultSize(w: WidgetType): Pair<Float, Float> = when (w) {
    WidgetType.BUTTON_MOMENTARY, WidgetType.BUTTON_LATCH, WidgetType.BUTTON_NAV -> 0.14f to 0.13f
    WidgetType.VALUE_OUT, WidgetType.VALUE_IN -> 0.16f to 0.15f
    WidgetType.LAMP -> 0.14f to 0.09f
    WidgetType.LEVEL -> 0.22f to 0.12f
    WidgetType.ALARM -> 0.16f to 0.10f
    WidgetType.LABEL -> 0.20f to 0.07f
}

data class Tag(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Oggetto",
    val widget: WidgetType = WidgetType.BUTTON_MOMENTARY,
    val db: Int = 1,
    val offset: Int = 0,
    val bit: Int = 0,
    val type: DataType = DataType.BOOL,
    // posizione e dimensione come frazione della tela (0..1)
    val x: Float = 0.4f,
    val y: Float = 0.4f,
    val w: Float = 0.14f,
    val h: Float = 0.13f,
    val color: Int = 0xFF2ECC71.toInt(),
    val useLimits: Boolean = false,
    val min: Float = 0f,
    val max: Float = 100f,
    val unit: String = "",
    val pageId: String = "",
    val target: String = "" // per BUTTON_NAV: id pagina, oppure __next / __prev
) {
    /** Indirizzo stile TIA Portal, es. DB1.DBX0.0 / DB1.DBW2 */
    fun address(): String = if (!widget.hasAddress) "" else when (type) {
        DataType.BOOL -> "DB$db.DBX$offset.$bit"
        DataType.BYTE -> "DB$db.DBB$offset"
        DataType.INT -> "DB$db.DBW$offset"
        else -> "DB$db.DBD$offset"
    }
}

data class PlcConfig(val ip: String = "192.168.0.1", val rack: Int = 0, val slot: Int = 1)

data class Page(val id: String = UUID.randomUUID().toString(), val name: String = "Pagina")

fun Page.toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

fun Tag.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("widget", widget.name)
    .put("db", db).put("offset", offset).put("bit", bit).put("type", type.name)
    .put("x", x.toDouble()).put("y", y.toDouble()).put("w", w.toDouble()).put("h", h.toDouble())
    .put("color", color).put("useLimits", useLimits)
    .put("min", min.toDouble()).put("max", max.toDouble()).put("unit", unit)
    .put("pageId", pageId).put("target", target)

fun tagFromJson(o: JSONObject): Tag {
    val d = Tag()
    val wt = try { WidgetType.valueOf(o.getString("widget")) } catch (e: Exception) { WidgetType.VALUE_OUT }
    val dt = try { DataType.valueOf(o.getString("type")) } catch (e: Exception) { DataType.INT }
    return Tag(
        id = o.getString("id"), name = o.optString("name", d.name), widget = wt,
        db = o.optInt("db", 1), offset = o.optInt("offset", 0), bit = o.optInt("bit", 0), type = dt,
        x = o.optDouble("x", d.x.toDouble()).toFloat(), y = o.optDouble("y", d.y.toDouble()).toFloat(),
        w = o.optDouble("w", d.w.toDouble()).toFloat(), h = o.optDouble("h", d.h.toDouble()).toFloat(),
        color = o.optInt("color", d.color), useLimits = o.optBoolean("useLimits", false),
        min = o.optDouble("min", 0.0).toFloat(), max = o.optDouble("max", 100.0).toFloat(),
        unit = o.optString("unit", ""),
        pageId = o.optString("pageId", ""), target = o.optString("target", "")
    )
}

/** Big-endian, come in memoria S7. */
fun decodeNumber(type: DataType, b: ByteArray): Double = when (type) {
    DataType.BYTE -> (b[0].toInt() and 0xFF).toDouble()
    DataType.INT -> ByteBuffer.wrap(b).short.toDouble()
    DataType.DINT -> ByteBuffer.wrap(b).int.toDouble()
    DataType.REAL -> ByteBuffer.wrap(b).float.toDouble()
    DataType.BOOL -> error("BOOL non è numerico")
}

fun encodeNumber(type: DataType, v: Double): ByteArray = when (type) {
    DataType.BYTE -> byteArrayOf(v.toInt().toByte())
    DataType.INT -> ByteBuffer.allocate(2).putShort(v.toInt().toShort()).array()
    DataType.DINT -> ByteBuffer.allocate(4).putInt(v.toInt()).array()
    DataType.REAL -> ByteBuffer.allocate(4).putFloat(v.toFloat()).array()
    DataType.BOOL -> error("BOOL non è numerico")
}

/** Il valore entra nel tipo di dato del PLC? */
fun fitsType(type: DataType, v: Double): Boolean = when (type) {
    DataType.BYTE -> v in 0.0..255.0
    DataType.INT -> v in -32768.0..32767.0
    DataType.DINT -> v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
    DataType.REAL -> true
    DataType.BOOL -> false
}
