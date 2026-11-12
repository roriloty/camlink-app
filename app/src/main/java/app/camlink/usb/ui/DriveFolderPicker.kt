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

private enum class Place { TOP, MY_DRIVE, SHARED_WITH_ME, SHARED_DRIVES, FOLDER }

private data class Crumb(val place: Place, val id: String, val name: String, val canAdd: Boolean)

private class Entry(val crumb: Crumb, val subtitle: String? = null)

/** Browses My Drive, "Shared with me" and shared drives; returns the folder id and a readable path. */
@Composable
fun DriveFolderPicker(account: String, onPick: (id: String, path: String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf(Crumb(Place.TOP, "", "Google Drive", false))) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    val here = stack.last()

    BackHandler { if (stack.size > 1) stack = stack.dropLast(1) else onClose() }

    suspend fun <T> drive(block: (String) -> T): T = withContext(Dispatchers.IO) { block(DriveAuth.tokenBlocking(context, account)) }

    LaunchedEffect(stack, reload) {
        entries = null; error = null
        try {
            entries = when (here.place) {
                Place.TOP -> listOf(
                    Entry(Crumb(Place.MY_DRIVE, "root", "My Drive", true), "Your own files"),
                    Entry(Crumb(Place.SHARED_WITH_ME, "", "Shared with me", false), "Folders other people shared with you"),
                    Entry(Crumb(Place.SHARED_DRIVES, "", "Shared drives", false), "Team drives you're a member of"),
                )
                Place.SHARED_WITH_ME -> drive { DriveApi.sharedWithMe(it) }.map { Entry(Crumb(Place.FOLDER, it.id, it.name, it.canAdd), if (it.canAdd) null else "View only") }
                Place.SHARED_DRIVES -> drive { DriveApi.sharedDrives(it) }.map { Entry(Crumb(Place.FOLDER, it.id, it.name, it.canAdd), if (it.canAdd) null else "View only") }
                Place.MY_DRIVE, Place.FOLDER -> drive { DriveApi.folders(it, here.id) }.map { Entry(Crumb(Place.FOLDER, it.id, it.name, it.canAdd), if (it.canAdd) null else "View only") }
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    val pickable = here.place == Place.MY_DRIVE || here.place == Place.FOLDER
    val path = stack.drop(1).joinToString(" / ") { it.name }

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF26262E))
                    .clickable { if (stack.size > 1) stack = stack.dropLast(1) else onClose() },
                    contentAlignment = Alignment.Center) { Text(if (stack.size > 1) "‹" else "✕", color = Color.White, fontSize = 18.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(here.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (path.isEmpty()) account else path, color = SettingsDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (pickable && here.canAdd) TextButton(onClick = { newFolder = "" }) { Text("+ New folder", color = SettingsAccent) }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = entries
                when {
                    error != null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(error!!, color = Color(0xFFE57373), fontSize = 14.sp)
                        Button(onClick = { reload++ }) { Text("Try again") }
                    }
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text(if (here.place == Place.SHARED_DRIVES) "No shared drives" else "No folders here",
                        color = SettingsDim, modifier = Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.crumb.place.name + it.crumb.id + it.crumb.name }) { e ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SettingsCard)
                                .clickable { stack = stack + e.crumb }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (here.place == Place.TOP) "◆" else "▰", color = if (e.crumb.canAdd || here.place == Place.TOP) SettingsAccent else SettingsDim, fontSize = 14.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.crumb.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    e.subtitle?.let { Text(it, color = SettingsDim, fontSize = 11.sp) }
                                }
                                Text("›", color = SettingsDim, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }

            // Bottom bar: choose the folder being viewed.
            if (pickable) Column(Modifier.fillMaxWidth().background(SettingsCard).padding(16.dp)) {
                Button(onClick = { onPick(here.id, path) }, enabled = here.canAdd, modifier = Modifier.fillMaxWidth()) {
                    Text(if (here.canAdd) "Upload to “${here.name}”" else "You can't add files to this folder")
                }
            }
        }
    }

    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder in ${here.name}") },
            text = { OutlinedTextField(name, { newFolder = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val n = name.trim(); newFolder = null
                    scope.launch {
                        try {
                            val id = drive { DriveApi.createFolder(it, here.id, n) }
                            stack = stack + Crumb(Place.FOLDER, id, n, true)
                        } catch (e: Exception) { error = e.message }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }
}
