package app.camlink.usb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.camlink.usb.lite.TetherService
import app.camlink.usb.upload.FtpSettingsStore
import app.camlink.usb.upload.Uploads

enum class AppMode { CONTROL, UPLOAD }

/** Startup question: full camera control, or the low-power upload-only tether. */
@Composable
fun ModeChooser(last: AppMode?, remember0: Boolean, onChoose: (AppMode, Boolean) -> Unit) {
    var always by remember { mutableStateOf(remember0) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).widthIn(max = 520.dp).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("CamLink", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("How do you want to use the camera?", color = SettingsDim, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            ModeCard(
                "Camera control", "Live view, tap to focus, aperture, shutter, ISO, white balance and a remote shutter. " +
                    "Photos are saved and uploaded too. Keeps the screen on, so it uses more battery.",
                "◉", last == AppMode.CONTROL,
            ) { onChoose(AppMode.CONTROL, always) }
            ModeCard(
                "Upload only", "No live view. Shoot with the camera's own shutter; every photo is saved, auto-edited and " +
                    "uploaded in the background, even with the phone locked. Saves power on long shoots.",
                "↑", last == AppMode.UPLOAD,
            ) { onChoose(AppMode.UPLOAD, always) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { always = !always }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = always, onCheckedChange = { always = it })
                Column {
                    Text("Always start in the mode I pick", color = Color.White, fontSize = 14.sp)
                    Text("You can change it later with “Change mode”.", color = SettingsDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, text: String, icon: String, lastUsed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SettingsCard)
            .border(1.dp, if (lastUsed) SettingsAccent.copy(alpha = 0.6f) else Color(0xFF26262E), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(SettingsAccent), contentAlignment = Alignment.Center) {
            Text(icon, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (lastUsed) Text("  last used", color = SettingsAccent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(text, color = SettingsDim, fontSize = 13.sp)
        }
    }
}

/** Upload-only mode: status, counts and the upload gallery on a black, mostly static screen. */
@Composable
fun UploadOnlyScreen(onStart: () -> Unit, onStop: () -> Unit, onChangeMode: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(onClose = { showSettings = false }); return }

    val st by TetherService.state.collectAsStateWithLifecycle()
    val up by Uploads.state.collectAsStateWithLifecycle()
    val ftp by FtpSettingsStore.flow(LocalContext.current).collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    when { st.connected -> Color(0xFF66BB6A); st.running -> SettingsAccent; else -> Color(0xFF555555) }))
                Spacer(Modifier.size(10.dp))
                Text("Upload only", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Change mode", color = SettingsAccent, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onChangeMode).padding(8.dp))
            }
            Text(if (st.running) "${st.model} · ${st.status}" else st.status, color = SettingsDim, fontSize = 14.sp)

            Row(verticalAlignment = Alignment.Bottom) {
                Text("${st.saved}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.size(8.dp))
                Text("saved this session" + (st.lastName?.let { " · last $it" } ?: ""), color = SettingsDim, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 7.dp))
            }

            val f = ftp
            val upText = when {
                f == null || !f.anyDestination -> "Upload is off (no FTP or Google Drive set up), so photos are only saved to the phone"
                up.current != null && up.editing -> "Editing ${up.current} · ${up.pending} in queue"
                up.current != null -> "Uploading ${up.current} ${(up.progress * 100).toInt()}% · ${up.pending} in queue"
                up.pending > 0 -> "${up.pending} waiting to upload"
                else -> "Upload ready" + (if (f.autoEdit) " · auto-edit on" else "")
            }
            Text(upText, color = Color.White, fontSize = 14.sp)
            Text("${up.uploaded} uploaded" + (if (up.failed > 0) " · ${up.failed} failed" else ""), color = SettingsDim, fontSize = 13.sp)
            up.lastError?.takeIf { up.failed > 0 }?.let { Text(it, color = Color(0xFFE57373), fontSize = 12.sp) }

            UploadGallery(Modifier.weight(1f).fillMaxWidth(), tileSize = 96)
            Text("No live view, so it uses less power. Lock the phone if you like; photos keep arriving and uploading.",
                color = SettingsDim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (st.running) OutlinedButton(onClick = onStop, Modifier.weight(1f)) { Text("Stop") }
                else Button(onClick = onStart, Modifier.weight(1f)) { Text("Connect camera") }
                OutlinedButton(onClick = { showSettings = true }, Modifier.weight(1f)) { Text("Upload settings") }
            }
        }
    }
}
