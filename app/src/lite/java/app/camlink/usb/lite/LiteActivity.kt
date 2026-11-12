package app.camlink.usb.lite

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.camlink.usb.ptp.PtpUsb
import app.camlink.usb.ptp.SonyCamera
import app.camlink.usb.ui.SettingsScreen
import app.camlink.usb.upload.FtpSettingsStore
import app.camlink.usb.upload.Uploads

/** Black, mostly static screen: starts the tether service and shows counts. Safe to lock the phone. */
class LiteActivity : ComponentActivity() {

    private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) TetherService.start(context, device)
                    else Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show()
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> tryStart(device)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LiteScreen(onStart = { scanAndStart() }, onStop = { TetherService.stop(this) })
            }
        }
        intent?.usbDevice()?.let { tryStart(it) } ?: scanAndStart(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { tryStart(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun scanAndStart(quiet: Boolean = false) {
        val cams = usb.deviceList.values.filter { PtpUsb.findPtpInterface(it) != null }
        val device = cams.firstOrNull { it.vendorId == SonyCamera.SONY_VENDOR_ID } ?: cams.firstOrNull()
        if (device == null) {
            if (!quiet) Toast.makeText(this,
                "No camera found. Use a USB-OTG cable and set the camera to USB Connection → PC Remote.",
                Toast.LENGTH_LONG).show()
            return
        }
        tryStart(device)
    }

    private fun tryStart(device: UsbDevice) {
        if (PtpUsb.findPtpInterface(device) == null || TetherService.state.value.running) return
        if (usb.hasPermission(device)) {
            TetherService.start(this, device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
            usb.requestPermission(device, pi)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object { private const val ACTION_USB_PERMISSION = "app.camlink.usb.lite.USB_PERMISSION" }
}

private val Accent = Color(0xFFFFB300)
private val Dim = Color(0xFF8A8A92)

@Composable
private fun LiteScreen(onStart: () -> Unit, onStop: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) { SettingsScreen(onClose = { showSettings = false }); return }

    val st by TetherService.state.collectAsStateWithLifecycle()
    val up by Uploads.state.collectAsStateWithLifecycle()
    val ftp by FtpSettingsStore.flow(androidx.compose.ui.platform.LocalContext.current).collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(
                    when { st.connected -> Color(0xFF66BB6A); st.running -> Accent; else -> Color(0xFF555555) }))
                Spacer(Modifier.size(10.dp))
                Text("CamLink Lite", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (st.running) "${st.model} · ${st.status}" else st.status, color = Dim, fontSize = 14.sp)

            Row(verticalAlignment = Alignment.Bottom) {
                Text("${st.saved}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.size(8.dp))
                Text("saved this session" + (st.lastName?.let { " · last $it" } ?: ""), color = Dim, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 7.dp))
            }

            val f = ftp
            val upText = when {
                f == null || !f.anyDestination -> "Upload is off (no FTP or Google Drive set up) — photos are only saved to the phone"
                up.current != null && up.editing -> "Editing ${up.current} · ${up.pending} in queue"
                up.current != null -> "Uploading ${up.current} ${(up.progress * 100).toInt()}% · ${up.pending} in queue"
                up.pending > 0 -> "${up.pending} waiting to upload"
                else -> "Upload ready" + (if (f.autoEdit) " · auto-edit on" else "")
            }
            Text(upText, color = Color.White, fontSize = 14.sp)
            Text("${up.uploaded} uploaded" + (if (up.failed > 0) " · ${up.failed} failed" else ""), color = Dim, fontSize = 13.sp)
            up.lastError?.takeIf { up.failed > 0 }?.let { Text(it, color = Color(0xFFE57373), fontSize = 12.sp) }

            app.camlink.usb.ui.UploadGallery(Modifier.weight(1f).fillMaxWidth(), tileSize = 96)
            Text("No live view, so it uses less power. Lock the phone if you like; photos keep arriving and uploading.",
                color = Dim, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (st.running) OutlinedButton(onClick = onStop, Modifier.weight(1f)) { Text("Stop") }
                else Button(onClick = onStart, Modifier.weight(1f)) { Text("Connect camera") }
                OutlinedButton(onClick = { showSettings = true }, Modifier.weight(1f)) { Text("Upload settings") }
            }
        }
    }
}
