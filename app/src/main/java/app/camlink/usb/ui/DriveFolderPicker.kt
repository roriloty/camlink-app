package app.camlink.usb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.camlink.usb.upload.DriveApi
import app.camlink.usb.upload.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the Drive folder to upload into. With the limited Drive permission CamLink only sees folders it
 * created itself, wherever they were moved to (also into a shared drive), so this lists those and can
 * create a new one in My Drive.
 */
@Composable
fun DriveFolderPicker(account: String, onPick: (id: String, path: String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<DriveApi.Folder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    suspend fun <T> drive(block: (String) -> T): T = withContext(Dispatchers.IO) { block(DriveAuth.tokenBlocking(context, account)) }

    LaunchedEffect(reload) {
        folders = null; error = null
        try { folders = drive { DriveApi.appFolders(it) } } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF26262E)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center) { Text("✕", color = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Upload folder", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(account, color = SettingsDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            Text(
                "CamLink can only see folders it created. To upload into a shared drive or share with others, " +
                    "move or share the folder in Google Drive. CamLink keeps access to it.",
                color = SettingsDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val list = folders
                when {
                    error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error!!, color = Color(0xFFE57373), fontSize = 14.sp)
                        Button(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text("No CamLink folders yet. Create one below.", color = SettingsDim,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.id }) { f ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SettingsCard)
                                .clickable { onPick(f.id, f.name) }.padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("▰", color = SettingsAccent, fontSize = 14.sp)
                                Spacer(Modifier.width(12.dp))
                                Text(f.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Use", color = SettingsAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().background(SettingsCard).padding(16.dp)) {
                Button(onClick = { newFolder = "CamLink" }, enabled = !creating, modifier = Modifier.fillMaxWidth()) {
                    if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Text("+ New folder in My Drive")
                }
            }
        }
    }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder in My Drive") },
            text = { OutlinedTextField(name, { newFolder = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = name.trim(); newFolder = null; creating = true
                    scope.launch {
                        try {
                            val id = drive { DriveApi.createFolder(it, "root", n) }
                            onPick(id, n)
                        } catch (e: Exception) { error = e.message }
                        creating = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }
}
