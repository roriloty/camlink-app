package app.camlink.usb

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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import app.camlink.usb.lite.TetherService
import app.camlink.usb.ptp.PtpUsb
import app.camlink.usb.ptp.SonyCamera
import app.camlink.usb.ui.AppMode
import app.camlink.usb.ui.CameraScreen
import app.camlink.usb.ui.ModeChooser
import app.camlink.usb.ui.UploadOnlyScreen

/**
 * One app, two modes chosen at startup: full camera control (live view, settings, shutter) or
 * upload only (low-power background tether that just saves, auto-edits and uploads).
 */
class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()
    private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val prefs by lazy { getSharedPreferences("app_mode", MODE_PRIVATE) }

    /** null = show the startup question. */
    private val mode = mutableStateOf<AppMode?>(null)
    /** Camera that was plugged in before a mode was picked. */
    private var pendingDevice: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> vm.disconnect("Camera unplugged")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> tryConnect(device)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Coming back while the background tether runs: stay in upload mode. Otherwise ask, unless remembered.
        mode.value = when {
            TetherService.state.value.running -> AppMode.UPLOAD
            savedInstanceState?.getString("mode") != null -> AppMode.valueOf(savedInstanceState.getString("mode")!!)
            prefs.getBoolean("remember", false) -> lastMode()
            else -> null
        }
        applyWindowMode()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFB300))) {
                when (mode.value) {
                    null -> ModeChooser(lastMode(), prefs.getBoolean("remember", false)) { m, always -> choose(m, always) }
                    AppMode.CONTROL -> CameraScreen(vm, onConnect = { scanAndConnect() }, onChangeMode = { changeMode() })
                    AppMode.UPLOAD -> UploadOnlyScreen(onStart = { scanAndConnect() }, onStop = { TetherService.stop(this) },
                        onChangeMode = { changeMode() })
                }
            }
        }

        pendingDevice = intent?.usbDevice()
        if (mode.value != null) pendingDevice?.let { tryConnect(it) } ?: scanAndConnect(quiet = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mode.value?.let { outState.putString("mode", it.name) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { tryConnect(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun lastMode(): AppMode? = prefs.getString("last", null)?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }

    private fun choose(m: AppMode, always: Boolean) {
        prefs.edit().putString("last", m.name).putBoolean("remember", always).apply()
        mode.value = m
        applyWindowMode()
        if (m == AppMode.UPLOAD && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        pendingDevice?.let { pendingDevice = null; tryConnect(it) } ?: scanAndConnect(quiet = true)
    }

    /** Releases the camera from the current mode and shows the startup question again. */
    private fun changeMode() {
        when (mode.value) {
            AppMode.CONTROL -> vm.disconnect("Disconnected")
            AppMode.UPLOAD -> TetherService.stop(this)
            null -> {}
        }
        mode.value = null
        applyWindowMode()
    }

    /** Camera control keeps the screen on for live view; upload only lets the phone sleep to save power. */
    private fun applyWindowMode() {
        if (mode.value == AppMode.CONTROL) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun connect(device: UsbDevice) {
        when (mode.value) {
            AppMode.CONTROL -> vm.connect(usb, device)
            AppMode.UPLOAD -> if (!TetherService.state.value.running) TetherService.start(this, device)
            null -> pendingDevice = device
        }
    }

    private fun scanAndConnect(quiet: Boolean = false) {
        val cams = usb.deviceList.values.filter { PtpUsb.findPtpInterface(it) != null }
        val device = cams.firstOrNull { it.vendorId == SonyCamera.SONY_VENDOR_ID } ?: cams.firstOrNull()
        if (device == null) {
            if (!quiet) Toast.makeText(this,
                "No camera found. Use a USB-OTG cable and set the camera to USB Connection → PC Remote.",
                Toast.LENGTH_LONG).show()
            return
        }
        tryConnect(device)
    }

    private fun tryConnect(device: UsbDevice) {
        if (PtpUsb.findPtpInterface(device) == null) return
        if (mode.value == null) { pendingDevice = device; return }
        if (usb.hasPermission(device)) {
            connect(device)
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

    companion object { private const val ACTION_USB_PERMISSION = "app.camlink.usb.USB_PERMISSION" }
}
