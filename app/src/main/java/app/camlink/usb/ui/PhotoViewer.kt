package app.camlink.usb.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import app.camlink.usb.SavedShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen viewer for this session's photos: swipe between shots, pinch / double-tap to zoom.
 * JPEGs are decoded at screen resolution from the saved originals; RAW files show their embedded preview.
 */
@Composable
fun PhotoViewer(shots: List<SavedShot>, start: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (shots.size - 1).coerceAtLeast(0))) { shots.size }
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onIndex(it) } }
    // A new shot arriving shifts the list; stay on the photo that is open.
    LaunchedEffect(start, shots.size) { if (pager.currentPage != start) pager.scrollToPage(start) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize(), key = { shots[it].uri.toString() }) { page ->
            ZoomableShot(shots[page], onZoomChanged = { if (page == pager.currentPage) zoomed = it })
        }

        val shot = shots.getOrNull(pager.currentPage)
        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 18.sp) }
            Spacer(Modifier.width(12.dp))
            if (shot != null) {
                Column(Modifier.weight(1f)) {
                    Text(shot.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("%.1f MB · %d of %d".format(shot.sizeBytes / 1_048_576.0, pager.currentPage + 1, shots.size),
                        color = Color(0xFFBBBBBB), fontSize = 12.sp)
                }
                Text("Open in Gallery", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFFFB300))
                        .clickable { openExternally(context, shot.uri, shot.isJpeg) }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ZoomableShot(shot: SavedShot, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = shot.thumb, shot.uri) {
        value = withContext(Dispatchers.IO) { loadForScreen(context, shot) } ?: shot.thumb
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun setScale(s: Float) {
        scale = s.coerceIn(1f, 6f)
        if (scale == 1f) offset = Offset.Zero
        onZoomChanged(scale > 1.01f)
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(shot.uri) {
                detectTapGestures(onDoubleTap = { setScale(if (scale > 1.01f) 1f else 2.5f) })
            }
            .pointerInput(shot.uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f || scale > 1.01f) {
                        setScale(scale * zoom)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img == null) {
            CircularProgressIndicator()
        } else {
            Image(img, shot.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                })
        }
        if (!shot.isJpeg) {
            Text("RAW preview", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

/** Decodes the saved file at roughly screen size (a 24 MP JPEG is sub-sampled 4x), honouring EXIF rotation. */
private fun loadForScreen(context: Context, shot: SavedShot): ImageBitmap? = runCatching {
    val cr = context.contentResolver
    if (!shot.isJpeg) {
        // RAW (ARW): use the preview JPEG embedded in the file.
        val exif = cr.openInputStream(shot.uri)!!.use { ExifInterface(it) }
        return exif.thumbnailBitmap?.asImageBitmap()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(shot.uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    val target = 2400
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
    val bmp = cr.openInputStream(shot.uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = cr.openInputStream(shot.uri)!!.use { ExifInterface(it).rotationDegrees }
    rotate(bmp, rotation).asImageBitmap()
}.getOrNull()

private fun rotate(b: Bitmap, degrees: Int): Bitmap =
    if (degrees == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

private fun openExternally(context: Context, uri: Uri, jpeg: Boolean) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (jpeg) "image/jpeg" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
