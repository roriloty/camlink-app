package app.camlink.usb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.camlink.usb.upload.DriveApi
import app.camlink.usb.upload.DriveAuth
import app.camlink.usb.upload.FtpSettings
import app.camlink.usb.upload.FtpSettingsStore
import app.camlink.usb.upload.FtpUploader
import app.camlink.usb.upload.UploadFilter
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val SettingsAccent = Color(0xFFFFB300)
internal val SettingsDim = Color(0xFF9A9AA5)
internal val SettingsCard = Color(0xFF16161C)
private val Bg = Color(0xFF0B0B0F)
private val Good = Color(0xFF81C784)
private val Bad = Color(0xFFE57373)

/** Upload settings: FTP and/or Google Drive, what to send, folders. Works with or without a camera; saved on "Save". */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(FtpSettingsStore.load(context)) }
    var portText by remember { mutableStateOf(s.port.toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var driveError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    fun current(): FtpSettings = s.copy(port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 21)

    // ---- Google sign-in
    fun signedIn(r: AuthorizationResult) {
        val token = r.accessToken ?: run { signingIn = false; driveError = "Google didn't grant Drive access"; return }
        scope.launch {
            try {
                val email = withContext(Dispatchers.IO) { DriveApi.accountEmail(token) }
                s = if (email != s.driveAccount) s.copy(driveAccount = email, driveFolderId = "", driveFolderPath = "") else s
                driveError = null
            } catch (e: Exception) { driveError = e.message }
            signingIn = false
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try { signedIn(DriveAuth.finish(context, res.data)) } catch (e: Exception) {
            signingIn = false; driveError = friendly(e)
        }
    }
    fun signIn() {
        signingIn = true; driveError = null
        scope.launch {
            try {
                val r = DriveAuth.begin(context)
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else signedIn(r)
            } catch (e: Exception) { signingIn = false; driveError = friendly(e) }
        }
    }

    if (picking) {
        DriveFolderPicker(s.driveAccount,
            onPick = { id, path -> s = s.copy(driveFolderId = id, driveFolderPath = path); picking = false },
            onClose = { picking = false })
        return
    }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            // ---- Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF26262E)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center) { Text("✕", color = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Upload", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(summary(current()), color = SettingsDim, fontSize = 12.sp)
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- FTP
                Section("FTP server", "Send photos to your own server", s.enabled, { s = s.copy(enabled = it) }) {
                    OutlinedTextField(s.host, { s = s.copy(host = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Server (host name or IP)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(portText, { portText = it.filter(Char::isDigit).take(5) }, Modifier.width(100.dp),
                            label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(s.directory, { s = s.copy(directory = it) }, Modifier.weight(1f),
                            label = { Text("Folder") }, placeholder = { Text("/photos") }, singleLine = true)
                    }
                    OutlinedTextField(s.username, { s = s.copy(username = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Username (blank = anonymous)") }, singleLine = true)
                    OutlinedTextField(s.password, { s = s.copy(password = it) }, Modifier.fillMaxWidth(),
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Text(if (showPassword) "Hide" else "Show", color = SettingsAccent, fontSize = 12.sp,
                                modifier = Modifier.clickable { showPassword = !showPassword }.padding(8.dp))
                        })
                    ToggleRow("Passive mode", "Recommended; turn off only if your server needs active mode", s.passive) { s = s.copy(passive = it) }
                    ToggleRow("Use FTPS (TLS)", "Encrypted login and transfer, if your server supports it", s.tls) { s = s.copy(tls = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !testing && s.host.isNotBlank(),
                            onClick = {
                                testing = true; testResult = null
                                val cfg = current()
                                scope.launch {
                                    testResult = withContext(Dispatchers.IO) {
                                        runCatching { FtpUploader.test(cfg) }.fold({ true to it }, { false to (it.message ?: it.toString()) })
                                    }
                                    testing = false
                                }
                            },
                        ) { Text("Test connection") }
                        if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    testResult?.let { (ok, msg) -> Text((if (ok) "✓ " else "✕ ") + msg, color = if (ok) Good else Bad, fontSize = 13.sp) }
                }

                // ---- Google Drive
                Section("Google Drive", "Into a CamLink folder in your Drive (move it into a shared drive if you like)", s.driveEnabled, { s = s.copy(driveEnabled = it) }) {
                    if (s.driveAccount.isBlank()) {
                        Button(onClick = { signIn() }, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) {
                            if (signingIn) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                            else Text("Sign in with Google")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF3B5BA9)), contentAlignment = Alignment.Center) {
                                Text(s.driveAccount.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.driveAccount, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Signed in", color = Good, fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                val acct = s.driveAccount
                                s = s.copy(driveAccount = "", driveFolderId = "", driveFolderPath = "")
                                scope.launch { DriveAuth.signOut(context, acct) }
                            }) { Text("Sign out", color = SettingsDim) }
                        }
                        HorizontalDivider(color = Color(0xFF26262E))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Upload folder", color = SettingsDim, fontSize = 11.sp)
                                Text(s.driveFolderPath.ifBlank { "Not chosen yet: tap Choose to create one" },
                                    color = if (s.driveFolderId.isBlank()) SettingsAccent else Color.White, fontSize = 14.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { picking = true }) { Text(if (s.driveFolderId.isBlank()) "Choose" else "Change") }
                        }
                    }
                    driveError?.let { Text(it, color = Bad, fontSize = 12.sp) }
                }

                // ---- What to upload
                Section("What to upload") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(UploadFilter.ALL to "All files", UploadFilter.JPEG_ONLY to "JPEG only", UploadFilter.RAW_ONLY to "RAW only")
                            .forEach { (f, label) ->
                                val on = s.filter == f
                                Text(label, color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) SettingsAccent else Color(0xFF26262E))
                                        .clickable { s = s.copy(filter = f) }.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                    }
                    ToggleRow(
                        "Auto-edit before upload",
                        "Your Lightroom look, learned from your newest edits. Photos on the phone stay original; RAW is sent as is.",
                        s.autoEdit,
                    ) { s = s.copy(autoEdit = it) }
                    ToggleRow(
                        "Also upload the unedited original",
                        if (s.autoEdit) "Send both versions of every JPEG" else "Only applies when auto-edit is on",
                        s.uploadOriginalToo, enabled = s.autoEdit,
                    ) { s = s.copy(uploadOriginalToo = it) }
                }

                // ---- Folders
                Section("Subfolders", "Inside the FTP folder and the Drive folder. If both versions end up together, the edited one is named …_edited.JPG.") {
                    ToggleRow("Originals in a subfolder", where(s, s.originalSub()), s.originalSubfolder) { s = s.copy(originalSubfolder = it) }
                    AnimatedVisibility(s.originalSubfolder) {
                        OutlinedTextField(s.originalFolderName, { s = s.copy(originalFolderName = it.replace("/", "")) },
                            Modifier.fillMaxWidth(), label = { Text("Originals subfolder name") }, singleLine = true)
                    }
                    ToggleRow("Edited in a subfolder", where(s, s.editedSub()), s.editedSubfolder, enabled = s.autoEdit) {
                        s = s.copy(editedSubfolder = it)
                    }
                    AnimatedVisibility(s.editedSubfolder && s.autoEdit) {
                        OutlinedTextField(s.editedFolderName, { s = s.copy(editedFolderName = it.replace("/", "")) },
                            Modifier.fillMaxWidth(), label = { Text("Edited subfolder name") }, singleLine = true)
                    }
                }

                // ---- Phone
                Section("On the phone") {
                    ToggleRow(
                        "Delete after upload",
                        "Removes the photo from Pictures/CamLink once every version reached every destination. " +
                            "Can't be undone; the camera's card keeps its copy if Save Dest. is PC+Camera.",
                        s.deleteAfterUpload,
                    ) { s = s.copy(deleteAfterUpload = it) }
                }
                Text("The FTP password is stored in this app's private storage. Google sign-in is handled by Google Play services.",
                    color = SettingsDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            // ---- Save
            Box(Modifier.fillMaxWidth().background(Bg).padding(16.dp)) {
                Button(onClick = { FtpSettingsStore.save(context, current()); onClose() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Save", fontSize = 16.sp)
                }
            }
        }
    }
}

/** "FTP + Drive · auto-edit" style one-liner for the header. */
private fun summary(s: FtpSettings): String {
    val dest = listOfNotNull("FTP".takeIf { s.enabled }, "Google Drive".takeIf { s.driveEnabled })
    if (dest.isEmpty()) return "Off — photos stay on the phone only"
    return "To " + dest.joinToString(" + ") + (if (s.autoEdit) " · auto-edit" else "")
}

/** Where a version lands, e.g. "FTP /photos/Edited · Drive My Drive / Shoot / Edited". */
private fun where(s: FtpSettings, sub: String): String {
    val parts = ArrayList<String>()
    if (s.enabled) {
        val ftp = listOf(s.directory.trim().trim('/'), sub).filter { it.isNotEmpty() }.joinToString("/")
        parts += "FTP /$ftp"
    }
    if (s.driveEnabled) {
        val base = s.driveFolderPath.ifBlank { "(folder)" }
        parts += "Drive " + if (sub.isEmpty()) base else "$base / $sub"
    }
    return parts.joinToString(" · ").ifEmpty { "Turn on FTP or Google Drive above" }
}

private fun friendly(e: Exception): String {
    val code = (e as? ApiException)?.statusCode
    return when (code) {
        10 -> "Google sign-in isn't set up for this app yet: it needs an Android OAuth client in Google Cloud Console (package name + SHA-1)."
        12501, 16 -> "Sign-in cancelled"
        7 -> "No internet connection"
        else -> e.message ?: e.toString()
    }
}

/** A rounded card with a title (and optional on/off switch); its content shows only while on. */
@Composable
private fun Section(
    title: String,
    subtitle: String? = null,
    checked: Boolean? = null,
    onChecked: ((Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val on = checked ?: true
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SettingsCard)
            .border(1.dp, if (checked == true) SettingsAccent.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                subtitle?.let { Text(it, color = SettingsDim, fontSize = 12.sp) }
            }
            if (checked != null && onChecked != null) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = checked, onCheckedChange = onChecked)
            }
        }
        if (on) content()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else SettingsDim, fontSize = 14.sp)
            Text(subtitle, color = SettingsDim, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}
