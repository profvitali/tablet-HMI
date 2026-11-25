package com.example.s7hmi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val Red = Color(0xFFE74C3C)
private val Grey = Color(0xFF4B5563)
private val Panel = Color(0xFF1F2937)
private val Palette = listOf(0xFF2ECC71, 0xFF3B82F6, 0xFFF59E0B, 0xFFE74C3C, 0xFF8B5CF6, 0xFF06B6D4, 0xFFE5E7EB).map { it.toInt() }

@Composable
fun HmiScreen(vm: HmiViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tag?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf<Tag?>(null) }
    var pageDialog by remember { mutableStateOf<String?>(null) } // "add" oppure "rename"
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setBackground(uri)
    }
    val currentPage = vm.pages.firstOrNull { it.id == vm.currentPageId }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("S7 HMI", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            StatusPill(vm.status, vm.connected)
            Spacer(Modifier.weight(1f))
            if (vm.editMode) {
                FilledTonalButton(onClick = { editing = Tag(); editingIsNew = true }) { Text("+ Oggetto") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text("Sfondo") }
                if (vm.background != null) {
                    TextButton(onClick = { vm.removeBackground() }) { Text("Togli sfondo") }
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = { showSettings = true }) { Text("PLC") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { vm.editMode = !vm.editMode }) {
                Text(if (vm.editMode) "Fine modifica" else "Modifica")
            }
        }
        if (vm.showTabs || vm.editMode) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vm.pages.forEach { p ->
                    FilterChip(selected = p.id == vm.currentPageId, onClick = { vm.selectPage(p.id) }, label = { Text(p.name) })
                }
                if (vm.editMode) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { pageDialog = "add" }) { Text("+ Pagina") }
                    OutlinedButton(onClick = { pageDialog = "rename" }) { Text("Rinomina") }
                    OutlinedButton(onClick = { vm.movePage(vm.currentPageId, -1) }) { Text("◀") }
                    OutlinedButton(onClick = { vm.movePage(vm.currentPageId, 1) }) { Text("▶") }
                    if (vm.pages.size > 1) TextButton(onClick = { confirmDelete = true }) { Text("Elimina pagina") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        PanelCanvas(vm, Modifier.weight(1f).fillMaxWidth(),
            onEdit = { editing = it; editingIsNew = false },
            onWrite = { writing = it })
    }

    if (showSettings) {
        SettingsDialog(vm.config, vm.showTabs,
            onSave = { c, tabs -> vm.saveConfig(c); vm.setShowTabs(tabs); showSettings = false },
            onDismiss = { showSettings = false })
    }
    editing?.let { t ->
        TagDialog(t, editingIsNew, vm.pages,
            onSave = { vm.upsertTag(it); editing = null },
            onDelete = { vm.removeTag(t); editing = null },
            onDuplicate = { vm.duplicateTag(t); editing = null },
            onDismiss = { editing = null })
    }
    writing?.let { t ->
        WriteDialog(t, onWrite = { vm.writeNumber(t, it); writing = null }, onDismiss = { writing = null })
    }
    pageDialog?.let { mode ->
        PageNameDialog(
            title = if (mode == "add") "Nuova pagina" else "Rinomina pagina",
            initial = if (mode == "add") "Pagina ${vm.pages.size + 1}" else (currentPage?.name ?: ""),
            onSave = { n ->
                if (mode == "add") vm.addPage(n) else vm.renamePage(vm.currentPageId, n)
                pageDialog = null
            },
            onDismiss = { pageDialog = null })
    }
    if (confirmDelete && currentPage != null) {
        val count = vm.tags.count { it.pageId == currentPage.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminare la pagina?") },
            text = { Text("«${currentPage.name}» e i suoi $count oggetti verranno eliminati.") },
            confirmButton = { TextButton(onClick = { vm.deletePage(currentPage.id); confirmDelete = false }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } })
    }
}

@Composable
fun StatusPill(text: String, ok: Boolean) {
    Surface(shape = RoundedCornerShape(50), color = if (ok) Color(0xFF14532D) else Color(0xFF7F1D1D)) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Tela del pannello: gli oggetti stanno in coordinate relative (0..1) e si adattano allo schermo. */
@Composable
fun PanelCanvas(vm: HmiViewModel, modifier: Modifier, onEdit: (Tag) -> Unit, onWrite: (Tag) -> Unit) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF111827))) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        vm.background?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        vm.tags.filter { it.pageId == vm.currentPageId }.forEach { tag ->
            key(tag.id) {
                val wDp = with(density) { (tag.w * wPx).toDp() }
                val hDp = with(density) { (tag.h * hPx).toDp() }
                Box(
                    Modifier
                        .offset { IntOffset((tag.x * wPx).roundToInt(), (tag.y * hPx).roundToInt()) }
                        .size(wDp, hDp)
                        .then(
                            if (vm.editMode) Modifier
                                .clickable { onEdit(tag) }
                                .pointerInput(tag.id, wPx, hPx) {
                                    detectDragGestures(onDragEnd = { vm.commitLayout() }) { change, drag ->
                                        change.consume()
                                        vm.moveTag(tag.id, drag.x / wPx, drag.y / hPx)
                                    }
                                }
                            else Modifier
                        )
                ) {
                    ObjectContent(tag, vm, onWrite)
                    if (vm.editMode) {
                        Box(Modifier.matchParentSize().border(1.dp, Color(0x99FFFFFF)))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(26.dp)
                                .background(MaterialTheme.colorScheme.primary)
                                .pointerInput(tag.id, wPx, hPx) {
                                    detectDragGestures(onDragEnd = { vm.commitLayout() }) { change, drag ->
                                        change.consume()
                                        vm.resizeTag(tag.id, drag.x / wPx, drag.y / hPx)
                                    }
                                }
                        )
                    }
                }
            }
        }
        if (vm.tags.none { it.pageId == vm.currentPageId }) {
            Text("Premi «Modifica», poi «+ Oggetto» per progettare il pannello",
                Modifier.align(Alignment.Center), color = Color(0xFF9CA3AF))
        }
    }
}

private fun fmt(t: Tag, v: Any?): String = when {
    v == null -> "—"
    v is Double -> (if (t.type == DataType.REAL) "%.2f".format(v) else "%.0f".format(v)) +
        (if (t.unit.isNotBlank()) " ${t.unit}" else "")
    else -> v.toString()
}

@Composable
fun ObjectContent(tag: Tag, vm: HmiViewModel, onWrite: (Tag) -> Unit) {
    val v = vm.values[tag.id]
    val canOperate = vm.connected && !vm.editMode
    val accent = Color(tag.color)
    val shape = RoundedCornerShape(10.dp)

    when (tag.widget) {
        WidgetType.BUTTON_MOMENTARY -> {
            var pressed by remember { mutableStateOf(false) }
            val lit = pressed || v == true
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (lit) accent else accent.copy(alpha = 0.25f))
                    .pointerInput(canOperate, tag) {
                        if (canOperate) detectTapGestures(onPress = {
                            pressed = true; vm.writeBool(tag, true)
                            tryAwaitRelease()
                            pressed = false; vm.writeBool(tag, false)
                        })
                    },
                contentAlignment = Alignment.Center
            ) { Text(tag.name, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
        }
        WidgetType.BUTTON_LATCH -> {
            val on = v == true
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (on) accent else accent.copy(alpha = 0.25f))
                    .clickable(enabled = canOperate) { vm.writeBool(tag, !on) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tag.name, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                    Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        WidgetType.VALUE_OUT, WidgetType.VALUE_IN -> {
            val isIn = tag.widget == WidgetType.VALUE_IN
            val out = tag.useLimits && v is Double && (v < tag.min || v > tag.max)
            Column(
                Modifier.fillMaxSize().clip(shape).background(Panel)
                    .then(if (isIn) Modifier.border(2.dp, accent, shape) else Modifier)
                    .clickable(enabled = isIn && canOperate) { onWrite(tag) }
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tag.name, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9CA3AF), maxLines = 1)
                Text(fmt(tag, v), style = MaterialTheme.typography.headlineSmall,
                    color = if (out) Red else accent, maxLines = 1)
                if (isIn) Text("tocca per scrivere", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7280))
            }
        }
        WidgetType.LAMP -> Row(
            Modifier.fillMaxSize().clip(shape).background(Panel).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (v == true) accent else Grey))
            Spacer(Modifier.width(10.dp))
            Text(tag.name, maxLines = 2)
        }
        WidgetType.LEVEL -> {
            val d = (v as? Double) ?: 0.0
            val span = (tag.max - tag.min).takeIf { it != 0f } ?: 1f
            val frac = ((d.toFloat() - tag.min) / span).coerceIn(0f, 1f)
            val out = v != null && (d < tag.min || d > tag.max)
            Column(
                Modifier.fillMaxSize().clip(shape).background(Panel).padding(10.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(tag.name, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9CA3AF), maxLines = 1)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                    color = if (out) Red else accent
                )
                Spacer(Modifier.height(4.dp))
                Text(fmt(tag, v), color = if (out) Red else Color.Unspecified)
            }
        }
        WidgetType.ALARM -> {
            val a by rememberInfiniteTransition(label = "blink").animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
            val active = v == true
            Box(
                Modifier.fillMaxSize().clip(shape)
                    .background(if (active) Red else Grey).alpha(if (active) a else 1f),
                contentAlignment = Alignment.Center
            ) { Text(tag.name, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
        }
        WidgetType.BUTTON_NAV -> Box(
            Modifier.fillMaxSize().clip(shape).background(accent.copy(alpha = 0.9f))
                .clickable(enabled = !vm.editMode) { vm.navigate(tag.target) },
            contentAlignment = Alignment.Center
        ) { Text(tag.name, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold, color = Color(0xFF0B1220)) }
        WidgetType.LABEL -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text(tag.name, style = MaterialTheme.typography.titleLarge, color = accent)
        }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(label(it)) }) }
    }
}

@Composable
fun PageNameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Nome pagina") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun SettingsDialog(cfg: PlcConfig, showTabs: Boolean, onSave: (PlcConfig, Boolean) -> Unit, onDismiss: () -> Unit) {
    var ip by remember { mutableStateOf(cfg.ip) }
    var rack by remember { mutableStateOf(cfg.rack.toString()) }
    var slot by remember { mutableStateOf(cfg.slot.toString()) }
    var tabs by remember { mutableStateOf(showTabs) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Progetto e connessione PLC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ip, { ip = it }, label = { Text("Indirizzo IP del PLC") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Rack", rack, { rack = it }, Modifier.weight(1f))
                    NumField("Slot", slot, { slot = it }, Modifier.weight(1f))
                }
                Text("S7-1200 (1214C): rack 0, slot 1", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = tabs, onCheckedChange = { tabs = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Mostra le schede delle pagine in alto (se spente, si naviga coi pulsanti «Vai a pagina»)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(PlcConfig(ip.trim(), rack.toIntOrNull() ?: 0, slot.toIntOrNull() ?: 1), tabs) }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun WriteDialog(tag: Tag, onWrite: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Scrivi: ${tag.name}") },
        text = {
            Column {
                NumField("Nuovo valore (${tag.type.name})", text, { text = it; error = null }, decimal = true)
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = text.replace(',', '.').toDoubleOrNull()
                when {
                    d == null -> error = "Numero non valido"
                    !fitsType(tag.type, d) -> error = "Fuori dal campo del tipo ${tag.type.name}"
                    tag.useLimits && (d < tag.min || d > tag.max) -> error = "Fuori limiti (${tag.min} … ${tag.max})"
                    else -> onWrite(d)
                }
            }) { Text("Scrivi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun TagDialog(initial: Tag, isNew: Boolean, pages: List<Page>, onSave: (Tag) -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var widget by remember { mutableStateOf(initial.widget) }
    var type by remember { mutableStateOf(initial.type) }
    var db by remember { mutableStateOf(initial.db.toString()) }
    var offset by remember { mutableStateOf(initial.offset.toString()) }
    var bit by remember { mutableStateOf(initial.bit.toString()) }
    var min by remember { mutableStateOf(initial.min.toString()) }
    var max by remember { mutableStateOf(initial.max.toString()) }
    var unit by remember { mutableStateOf(initial.unit) }
    var useLimits by remember { mutableStateOf(initial.useLimits) }
    var color by remember { mutableStateOf(initial.color) }
    var target by remember { mutableStateOf(initial.target.ifBlank { "__next" }) }

    val numeric = !widget.isBool && widget.hasAddress
    val effType = if (widget.isBool) DataType.BOOL else if (type == DataType.BOOL) DataType.INT else type
    val isValue = widget == WidgetType.VALUE_IN || widget == WidgetType.VALUE_OUT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nuovo oggetto" else "Modifica oggetto") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(if (widget == WidgetType.LABEL) "Testo" else "Nome") }, singleLine = true)
                Text("Tipo di oggetto", style = MaterialTheme.typography.labelMedium)
                ChipRow(WidgetType.values().toList(), widget, { it.label }) { widget = it }
                if (numeric) {
                    Text("Tipo di dato", style = MaterialTheme.typography.labelMedium)
                    ChipRow(listOf(DataType.BYTE, DataType.INT, DataType.DINT, DataType.REAL), effType, { it.name }) { type = it }
                }
                if (widget.hasAddress) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("DB", db, { db = it }, Modifier.weight(1f))
                        NumField("Offset byte", offset, { offset = it }, Modifier.weight(1f))
                        if (effType == DataType.BOOL) NumField("Bit 0-7", bit, { bit = it }, Modifier.weight(1f))
                    }
                }
                if (numeric) OutlinedTextField(unit, { unit = it }, label = { Text("Unità (es. °C, bar)") }, singleLine = true)
                if (isValue) Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useLimits, onCheckedChange = { useLimits = it })
                    Spacer(Modifier.width(8.dp)); Text("Limiti (fuori range in rosso, scrittura bloccata)")
                }
                if (widget == WidgetType.LEVEL || (isValue && useLimits)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Min", min, { min = it }, Modifier.weight(1f), decimal = true)
                    NumField("Max", max, { max = it }, Modifier.weight(1f), decimal = true)
                }
                if (widget == WidgetType.BUTTON_NAV) {
                    Text("Pagina di destinazione", style = MaterialTheme.typography.labelMedium)
                    val opts = listOf("__prev" to "◀ Precedente", "__next" to "Successiva ▶") + pages.map { it.id to it.name }
                    ChipRow(opts, opts.firstOrNull { it.first == target } ?: opts[1], { it.second }) { target = it.first }
                }
                Text("Colore", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Palette.forEach { c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                .border(if (c == color) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val base = if (isNew) {
                    val (dw, dh) = defaultSize(widget)
                    initial.copy(w = dw, h = dh, x = 0.5f - dw / 2, y = 0.5f - dh / 2)
                } else initial
                onSave(base.copy(
                    name = name.ifBlank { "Oggetto" }, widget = widget, type = effType,
                    db = db.toIntOrNull() ?: 1, offset = offset.toIntOrNull() ?: 0,
                    bit = (bit.toIntOrNull() ?: 0).coerceIn(0, 7),
                    min = min.replace(',', '.').toFloatOrNull() ?: 0f,
                    max = max.replace(',', '.').toFloatOrNull() ?: 100f,
                    unit = unit, useLimits = useLimits, color = color, target = target
                ))
            }) { Text("Salva") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("Elimina") }
                    TextButton(onClick = onDuplicate) { Text("Duplica") }
                }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        }
    )
}
