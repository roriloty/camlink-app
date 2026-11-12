package app.camlink.usb.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.camlink.usb.upload.ItemStatus
import app.camlink.usb.upload.UploadHistory
import app.camlink.usb.upload.UploadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val TileBg = Color(0xFF1C1C23)
private val Ok = Color(0xFF66BB6A)
private val Err = Color(0xFFE57373)

/** Full-screen upload gallery (full app). */
@Composable
fun UploadsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF26262E)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center) { Text("✕", color = Color.White) }
                Spacer(Modifier.width(12.dp))
                Text("Uploads", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            UploadGallery(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Queue / Uploaded tabs over a grid of small previews; tap one to see it and delete it. */
@Composable
fun UploadGallery(modifier: Modifier = Modifier, tileSize: Int = 104) {
    val context = LocalContext.current
    remember { UploadHistory.init(context) }
    val all by UploadHistory.items.collectAsStateWithLifecycle()
    val queue = all.filter { it.inQueue }
    val uploaded = all.filter { !it.inQueue }
    var tab by remember { mutableStateOf(if (queue.isNotEmpty()) 0 else 1) }
    var openId by remember { mutableStateOf<String?>(null) }
    val shown = if (tab == 0) queue else uploaded

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabChip("Queue · ${queue.size}", tab == 0) { tab = 0 }
            Spacer(Modifier.width(8.dp))
            TabChip("Uploaded · ${uploaded.size}", tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            if (tab == 1 && uploaded.isNotEmpty())
                Text("Clear list", color = SettingsDim, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { UploadHistory.clearUploaded(context) }.padding(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (tab == 0) "Nothing waiting to upload" else "No uploads yet", color = SettingsDim, fontSize = 13.sp)
            }
        } else {
            LazyVerticalGrid(
                GridCells.Adaptive(tileSize.dp), Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown, key = { it.id }) { item -> Tile(item) { openId = item.id } }
            }
        }
    }

    all.firstOrNull { it.id == openId }?.let { item -> Detail(item, onClose = { openId = null }) }
}

@Composable
private fun TabChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) SettingsAccent else Color(0xFF26262E))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun thumb(item: UploadItem): ImageBitmap? {
    val context = LocalContext.current
    val v by produceState<ImageBitmap?>(null, item.id, item.thumbVersion) {
        value = withContext(Dispatchers.IO) {
            val f = UploadHistory.thumbFile(context, item.id)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
        }
    }
    return v
}

@Composable
private fun Tile(item: UploadItem, onClick: () -> Unit) {
    val img = thumb(item)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(TileBg).clickable(onClick = onClick)) {
        if (img != null) Image(img, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(item.name.substringAfterLast('.').uppercase(), color = SettingsDim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center))

        when (item.status) {
            ItemStatus.DONE -> Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape)
                .background(if (item.error != null) Err else Ok), contentAlignment = Alignment.Center) {
                Text(if (item.error != null) "!" else "✓", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            ItemStatus.UPLOADING -> Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xAA000000))) {
                Text("${item.dest ?: ""} ${(item.progress * 100).toInt()}%", color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress.coerceIn(0f, 1f)).background(SettingsAccent))
                }
            }
            else -> {
                val (label, color) = when (item.status) {
                    ItemStatus.EDITING -> "Editing…" to SettingsAccent
                    ItemStatus.FAILED -> "Failed" to Err
                    ItemStatus.DELETING -> "Deleting…" to Err
                    else -> (if (item.error != null) "Retrying" else "Waiting") to Color.White
                }
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xAA000000))
                        .padding(horizontal = 5.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun Detail(item: UploadItem, onClose: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember(item.id) { mutableStateOf(false) }
    val img = thumb(item)
    val where = buildList {
        if (item.remotes.any { it.kind == "ftp" }) add("FTP")
        if (item.remotes.any { it.kind == "drive" }) add("Google Drive")
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF1A1A21),
        title = { Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).clip(RoundedCornerShape(10.dp)).background(TileBg),
                    contentAlignment = Alignment.Center) {
                    if (img != null) Image(img, item.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    else Text("No preview (RAW)", color = SettingsDim, modifier = Modifier.padding(40.dp))
                }
                Text(statusText(item), color = Color.White, fontSize = 14.sp)
                if (where.isNotEmpty()) Text("On " + where.joinToString(" and "), color = SettingsDim, fontSize = 12.sp)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.time)),
                    color = SettingsDim, fontSize = 12.sp)
                item.error?.let { Text(it, color = Err, fontSize = 12.sp) }
                if (confirm) Text(
                    "Delete this photo from the phone" +
                        (if (where.isNotEmpty()) ", " + where.joinToString(" and ") else "") +
                        (if (item.inQueue) " and stop its upload" else "") + "?" +
                        (if ("Google Drive" in where) " Drive copies go to Drive's trash (kept 30 days)." else "") +
                        " The camera's card is not touched.",
                    color = Err, fontSize = 13.sp)
            }
        },
        confirmButton = {
            if (item.status != ItemStatus.DELETING) {
                if (!confirm) TextButton(onClick = { confirm = true }) { Text("Delete", color = Err) }
                else TextButton(onClick = { UploadHistory.delete(context, item.id); onClose() }) {
                    Text("Delete everywhere", color = Err, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            Row {
                if (item.status == ItemStatus.FAILED && !confirm)
                    TextButton(onClick = { UploadHistory.retry(context, item.id); onClose() }) { Text("Retry", color = SettingsAccent) }
                TextButton(onClick = { if (confirm) confirm = false else onClose() }) { Text(if (confirm) "Cancel" else "Close", color = SettingsDim) }
            }
        },
    )
}

private fun statusText(i: UploadItem) = when (i.status) {
    ItemStatus.QUEUED -> if (i.error != null) "Waiting to retry" else "Waiting to upload"
    ItemStatus.EDITING -> "Auto-editing…"
    ItemStatus.UPLOADING -> "Uploading to ${i.dest} · ${(i.progress * 100).toInt()}%"
    ItemStatus.DONE -> if (i.error != null) "Uploaded (delete failed)" else "Uploaded"
    ItemStatus.FAILED -> "Upload failed"
    ItemStatus.DELETING -> "Deleting…"
}
