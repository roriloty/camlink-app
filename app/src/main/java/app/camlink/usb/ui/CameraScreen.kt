package app.camlink.usb.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.camlink.usb.CameraViewModel
import app.camlink.usb.DebugLog
import app.camlink.usb.FocusState
import app.camlink.usb.Phase
import app.camlink.usb.SettingUi
import app.camlink.usb.ptp.SonyCamera
import app.camlink.usb.UiState
import app.camlink.usb.upload.Uploads
import androidx.compose.runtime.saveable.rememberSaveable

private val Accent = Color(0xFFFFB300)
private val PanelBg = Color(0xFF111116)
private val KeyBg = Color(0xFF26262E)
private val Dim = Color(0xFF9A9AA5)

@Composable
fun CameraScreen(vm: CameraViewModel, onConnect: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(ui.toast) {
        ui.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toastShown() }
    }

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showUploads by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (ui.phase == Phase.Connected) CameraLayout(ui, frame, vm, onUploads = { showUploads = true }) { showSettings = true }
            else ConnectPanel(ui, onConnect, onUploads = { showUploads = true }) { showSettings = true }
            ui.viewing?.let { idx ->
                if (ui.shots.isNotEmpty()) PhotoViewer(ui.shots, idx, onIndex = { vm.view(it) }, onClose = { vm.view(null) })
            }
            if (showUploads) UploadsScreen(onClose = { showUploads = false })
            if (showSettings) SettingsScreen(onClose = { showSettings = false })
        }
    }
}

// ------------------------------------------------------------------ connected layout

@Composable
private fun CameraLayout(ui: UiState, frame: ImageBitmap?, vm: CameraViewModel, onUploads: () -> Unit, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth > maxHeight) {
            // Landscape: picture left, control column right.
            Row(Modifier.fillMaxSize()) {
                LiveView(frame, ui, Modifier.weight(1f).fillMaxHeight()) { fx, fy -> vm.focusAt(fx, fy) }
                Column(
                    Modifier.width(212.dp).fillMaxHeight().background(PanelBg)
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusRow(ui, vm, onUploads, onSettings)
                    SettingsGrid(ui, vm, columns = 2)
                    Adjuster(ui, vm)
                    ShutterButton(ui, vm)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { LastShot(ui, compact = true) { vm.view(0) } }
                        AfButton(vm)
                    }
                }
            }
        } else {
            // Portrait: status, picture, controls stacked; nothing drawn over the picture except the AF box.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().background(PanelBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    StatusRow(ui, vm, onUploads, onSettings)
                }
                LiveView(frame, ui, Modifier.weight(1f).fillMaxWidth()) { fx, fy -> vm.focusAt(fx, fy) }
                Column(Modifier.fillMaxWidth().background(PanelBg).padding(12.dp)) {
                    SettingsGrid(ui, vm, columns = 4)
                    Spacer(Modifier.height(10.dp))
                    Adjuster(ui, vm)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { LastShot(ui, compact = false) { vm.view(0) } }
                        ShutterButton(ui, vm)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { AfButton(vm) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(ui: UiState, vm: CameraViewModel, onUploads: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(ui.model, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.focusMode.isNotEmpty()) { Chip(ui.focusMode); Spacer(Modifier.width(6.dp)) }
                if (ui.savedCount > 0) Text("${ui.savedCount} saved", color = Dim, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                UploadIndicator()
            }
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(KeyBg).clickable(onClick = onUploads),
            contentAlignment = Alignment.Center,
        ) { Text("▦", color = Color.White, fontSize = 18.sp) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(KeyBg).clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) { Text("⚙", color = Color.White, fontSize = 18.sp) }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(KeyBg).clickable { vm.disconnect() },
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = Color.White, fontSize = 16.sp) }
    }
}

// ------------------------------------------------------------------ live view + tap to focus

@Composable
private fun LiveView(frame: ImageBitmap?, ui: UiState, modifier: Modifier, onTap: (Float, Float) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val imgRect: Rect? = frame?.let { fitRect(box, it.width, it.height) }

    Box(
        modifier.background(Color.Black)
            .onSizeChanged { box = it }
            .pointerInput(imgRect) {
                detectTapGestures { p ->
                    val r = imgRect ?: return@detectTapGestures
                    if (r.contains(p)) onTap((p.x - r.left) / r.width, (p.y - r.top) / r.height)
                }
            }
    ) {
        if (frame != null) {
            Image(frame, "Live view", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("Waiting for live view…", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }
        if (imgRect != null) FocusOverlay(ui, imgRect)
        ui.busy?.let {
            Text(it, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Accent).padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

private val Green = Color(0xFF4CAF50)
private val Red = Color(0xFFF44336)

/**
 * Draws the camera's own AF frame: the Flexible Spot box where the body has it, a centre box in Center,
 * corner brackets for Wide / Zone. White while focusing, green when locked — also when focusing with the
 * camera's shutter button. (The A7 III does not send which Wide-area points or faces it picked over USB.)
 */
@Composable
private fun FocusOverlay(ui: UiState, r: Rect) {
    val f = ui.camFocus ?: return
    val color = when {
        f.locked || ui.focusState == FocusState.Focused -> Green
        f.focusing || ui.focusState == FocusState.Focusing -> Color.White
        f.failed || ui.focusState == FocusState.Failed -> Red
        else -> Color(0xB3FFFFFF)
    }
    val stateLabel = when {
        f.locked || ui.focusState == FocusState.Focused -> "● AF locked"
        f.focusing || ui.focusState == FocusState.Focusing -> "○ Focusing"
        f.failed || ui.focusState == FocusState.Failed -> "✕ No focus"
        else -> null
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(2.5.dp.toPx())
            fun box(cx: Float, cy: Float, w: Float, h: Float, c: Color) =
                drawRect(c, topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h), style = stroke)
            fun brackets(rect: Rect, c: Color) {
                val l = minOf(rect.width, rect.height) * 0.08f
                val s = 3.dp.toPx()
                listOf(
                    rect.topLeft to Offset(1f, 1f), rect.topRight to Offset(-1f, 1f),
                    rect.bottomLeft to Offset(1f, -1f), rect.bottomRight to Offset(-1f, -1f),
                ).forEach { (p, d) ->
                    drawLine(c, p, p + Offset(d.x * l, 0f), s)
                    drawLine(c, p, p + Offset(0f, d.y * l), s)
                }
            }
            val spotW = r.width * f.size
            when (f.kind) {
                SonyCamera.AreaKind.SPOT -> {
                    // Pending tap (dashed-looking thin box) until the camera reports the new position.
                    ui.focusPoint?.let { tp ->
                        box(r.left + tp.first * r.width, r.top + tp.second * r.height, spotW, spotW * 0.8f, Color(0x99FFFFFF))
                    }
                    f.spot?.let { sp ->
                        if (ui.focusPoint == null) box(r.left + sp.first * r.width, r.top + sp.second * r.height, spotW, spotW * 0.8f, color)
                    }
                }
                SonyCamera.AreaKind.CENTER -> box(r.center.x, r.center.y, r.width * 0.12f, r.width * 0.1f, color)
                SonyCamera.AreaKind.ZONE -> brackets(Rect(r.center.x - r.width * 0.22f, r.center.y - r.height * 0.25f,
                    r.center.x + r.width * 0.22f, r.center.y + r.height * 0.25f), color)
                SonyCamera.AreaKind.WIDE -> brackets(Rect(r.left + r.width * 0.06f, r.top + r.height * 0.08f,
                    r.right - r.width * 0.06f, r.bottom - r.height * 0.08f), color)
            }
        }
        val area = ui.settings.firstOrNull { it.code == SonyCamera.DPC_FOCUS_AREA }?.value
        Text(
            listOfNotNull(ui.focusMode.ifEmpty { null }, area, stateLabel).joinToString("  ·  "),
            color = when (stateLabel) { "● AF locked" -> Green; "✕ No focus" -> Red; else -> Color.White }, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                .clip(RoundedCornerShape(8.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun fitRect(box: IntSize, w: Int, h: Int): Rect? {
    if (box.width == 0 || box.height == 0 || w == 0 || h == 0) return null
    val scale = minOf(box.width / w.toFloat(), box.height / h.toFloat())
    val dw = w * scale
    val dh = h * scale
    val l = (box.width - dw) / 2
    val t = (box.height - dh) / 2
    return Rect(l, t, l + dw, t + dh)
}

// ------------------------------------------------------------------ controls

/** Grid of exposure settings (tap to choose which one the ‹ › adjuster changes). */
@Composable
private fun SettingsGrid(ui: UiState, vm: CameraViewModel, columns: Int) {
    if (ui.settings.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ui.settings.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { st -> SettingTile(st, selected = ui.selected == st.code, Modifier.weight(1f)) { vm.select(st.code) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingTile(st: SettingUi, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val valueColor = when {
        st.readOnly -> Color.White
        st.adjustable -> Color.White
        else -> Dim
    }
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF3A3220) else KeyBg)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(enabled = !st.readOnly, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(st.label, color = if (selected) Accent else Dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(st.value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Big ‹ value › control for dial settings; a one-tap option picker for list settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Adjuster(ui: UiState, vm: CameraViewModel) {
    val st = ui.settings.firstOrNull { it.code == ui.selected } ?: return
    if (st.options.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            st.options.forEach { (value, label) ->
                val on = value == st.raw
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = when { on -> Color.Black; st.adjustable -> Color.White; else -> Dim },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) Accent else KeyBg)
                        .clickable(enabled = st.adjustable && !on) { vm.setOption(st.code, value) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (!st.adjustable) Text("${st.label}: not adjustable right now", color = Dim, fontSize = 10.sp)
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepKey("‹", st.adjustable) { vm.adjust(st.code, -1) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(st.value, color = if (st.adjustable) Color.White else Dim, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (st.adjustable) st.label else "${st.label}: not adjustable in this mode",
                color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        StepKey("›", st.adjustable) { vm.adjust(st.code, +1) }
    }
}

@Composable
private fun StepKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) KeyBg else Color(0xFF1A1A20))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) Color.White else Color(0xFF555560), fontSize = 26.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun ShutterButton(ui: UiState, vm: CameraViewModel) {
    val enabled = ui.busy == null
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
            .padding(7.dp).clip(CircleShape)
            .background(if (enabled) Color.White else Color.Gray)
            .clickable(enabled = enabled) { vm.shoot() }
    )
}

@Composable
private fun AfButton(vm: CameraViewModel) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).border(2.dp, Accent, CircleShape).clickable { vm.autoFocus() },
        contentAlignment = Alignment.Center,
    ) { Text("AF", color = Accent, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LastShot(ui: UiState, compact: Boolean, onOpen: () -> Unit) {
    val s = ui.lastSaved ?: return
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.DarkGray)) {
            s.thumb?.let { Image(it, s.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Column {
                Text(s.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%.1f MB".format(s.sizeBytes / 1_048_576.0), color = Dim, fontSize = 11.sp)
            }
        }
    }
}

/** Tiny background-upload status: "↑ 2 · 45%" with a hairline bar; nothing when idle. */
@Composable
private fun UploadIndicator() {
    val st by Uploads.state.collectAsStateWithLifecycle()
    val active = st.current != null || st.pending > 0
    if (!active && st.failed == 0) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            Column {
                Text(
                    "↑ ${st.pending}" + when {
                        st.editing -> " · editing"
                        st.current != null -> " · ${(st.progress * 100).toInt()}%"
                        else -> " waiting"
                    },
                    color = Dim, fontSize = 11.sp,
                )
                Box(Modifier.width(48.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(KeyBg)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(st.progress.coerceIn(0f, 1f)).background(Accent))
                }
            }
        }
        if (st.failed > 0) {
            Spacer(Modifier.width(6.dp))
            Text("↑✕${st.failed}", color = Color(0xFFE57373), fontSize = 11.sp)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Text(text, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 1.dp))
}

// ------------------------------------------------------------------ connect screen

@Composable
private fun ConnectPanel(ui: UiState, onConnect: () -> Unit, onUploads: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CamLink USB", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(ui.message, color = Color(0xFFBBBBBB))
        Spacer(Modifier.height(24.dp))
        if (ui.phase == Phase.Connecting) CircularProgressIndicator()
        else Button(onClick = onConnect) { Text("Connect camera") }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onUploads) { Text("▦  Uploads") }
            OutlinedButton(onClick = onSettings) { Text("⚙  Settings") }
        }
        Spacer(Modifier.height(6.dp))
        UploadIndicator()
        Spacer(Modifier.height(32.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF16161C)).padding(16.dp)
        ) {
            Text("Camera setup (Sony Alpha)", fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            listOf(
                "MENU → Setup → USB Connection → PC Remote",
                "MENU → Setup → PC Remote Settings → Still Img. Save Dest. → PC+Camera",
                "If shown: RAW+J PC Save Img → RAW & JPEG, and Save Image Size in PC → Original",
                "Connect the camera to the phone with a USB-C OTG cable (not through the PC)",
                "For tap-to-focus: Focus Area → Flexible Spot (the app switches to it if needed)",
                "Exposure mode is set with the camera's mode dial; aperture, shutter, ISO and WB from the app",
            ).forEachIndexed { i, s ->
                Text("${i + 1}. $s", color = Color(0xFFCCCCCC), fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Photos are saved untouched (original JPEG / ARW) to Pictures/CamLink.",
                color = Accent, fontSize = 13.sp)
        }
        val log by DebugLog.text.collectAsStateWithLifecycle()
        if (log.isNotEmpty()) {
            val clipboard = LocalClipboardManager.current
            val ctx = LocalContext.current
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Connection log", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(log))
                    Toast.makeText(ctx, "Log copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy log") }
            }
            Spacer(Modifier.height(6.dp))
            Text(log, color = Color(0xFF9CCC65), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF101014)).padding(10.dp))
        }
    }
}
