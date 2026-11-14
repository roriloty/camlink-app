package app.camlink.usb.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.camlink.usb.DebugLog
import app.camlink.usb.PhotoSaver
import app.camlink.usb.ptp.PtpUsb
import app.camlink.usb.ptp.SonyCamera
import app.camlink.usb.upload.Uploads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LiteState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val model: String = "",
    val status: String = "Plug the camera into the phone with a USB cable.",
    val saved: Int = 0,
    val lastName: String? = null,
)

/**
 * Low-power tether: no live view, no property polling. Sleeps until the camera reports a new
 * picture (event 0xC201, plus a slow fallback check), then downloads, saves and queues the upload
 * (auto-edit happens in the upload worker). Runs as a foreground service so the screen can be off.
 */
class TetherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var camera: SonyCamera? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var device: UsbDevice? = null

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    /** Stops on unplug even when the app's screen is closed. */
    private val detach = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            unplugged()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, detach,
            android.content.IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        @Suppress("DEPRECATION")
        val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (dev == null) { stopSelf(); return START_NOT_STICKY }
        startInForeground("Connecting to ${dev.productName ?: "camera"}…")
        if (device == null) {
            device = dev
            _state.update { it.copy(running = true, model = dev.productName ?: "Sony camera") }
            scope.launch { run(dev) }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(dev: UsbDevice) {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        // A partial wake lock keeps the USB event reader alive with the screen off (CPU only; much cheaper than the screen).
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamLinkLite:tether").apply { acquire() }
        var reconnects = 0
        while (scope.isActive) {
            val cam = try {
                status("Connecting…")
                open(usb, dev)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                DebugLog.d("Lite connect failed: ${e.message}")
                if (++reconnects > 3) { status("Could not connect: ${e.message}"); stopSelf(); return }
                delay(1000L * reconnects); continue
            }
            camera = cam
            reconnects = 0
            _state.update { it.copy(connected = true) }
            status("Waiting for photos")
            try {
                listen(cam)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                DebugLog.d("Lite connection lost: ${e.message}")
                _state.update { it.copy(connected = false) }
                runCatching { cam.close() }
                camera = null
                if (++reconnects > 3) { status("Connection lost: ${e.message}"); stopSelf(); return }
                delay(800L * reconnects)
            }
        }
    }

    private fun open(usb: UsbManager, dev: UsbDevice): SonyCamera {
        val cam = SonyCamera(PtpUsb.open(usb, dev))
        try { cam.connect() } catch (e: Exception) { cam.close(); throw e }
        cam.setEventListener { code, _ -> if (code == EVENT_OBJECT_ADDED) wake.trySend(Unit) }
        return cam
    }

    /** Idles on the event pipe; checks the camera only when it says a picture is ready (or every 10 s as a fallback). */
    private suspend fun listen(cam: SonyCamera) {
        var failures = 0
        while (scope.isActive) {
            val event = withTimeoutOrNull(FALLBACK_CHECK_MS) { wake.receive() } != null
            if (event) delay(300) // let the camera finish writing
            try {
                cam.refreshProps()
                var guard = 0
                while (cam.pendingImages() > 0 && guard++ < 10) {
                    status("Downloading…")
                    val img = cam.downloadImage()
                    cam.refreshProps()
                    val uri = PhotoSaver.save(this, img.fileName, img.bytes)
                    Uploads.enqueue(this, uri, img.fileName, img.bytes.size.toLong())
                    _state.update { it.copy(saved = it.saved + 1, lastName = img.fileName) }
                }
                if (guard > 0) status("Waiting for photos")
                failures = 0
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "lite check failed", e)
                // The body stalls the pipe while it writes; clear it and try again.
                runCatching { cam.recover() }
                if (++failures >= 3) throw e
                delay(500)
                wake.trySend(Unit)
            }
        }
    }

    private fun status(text: String) {
        _state.update { it.copy(status = text) }
        val s = _state.value
        val line = if (s.saved > 0) "$text · ${s.saved} saved" else text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(line))
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camera tether", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notification(text),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, app.camlink.usb.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TetherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(_state.value.model.ifBlank { "CamLink" })
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(detach) }
        scope.cancel()
        camera?.let { runCatching { it.close() } }
        camera = null
        wakeLock?.let { if (it.isHeld) it.release() }
        _state.update { it.copy(running = false, connected = false,
            status = if (it.status.startsWith("Could not") || it.status.startsWith("Connection lost") ||
                it.status.startsWith("Camera unplugged")) it.status else "Stopped") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CamLinkLite"
        private const val CHANNEL = "tether"
        private const val NOTIF_ID = 1
        private const val EVENT_OBJECT_ADDED = 0xC201
        private const val FALLBACK_CHECK_MS = 10_000L
        const val ACTION_STOP = "app.camlink.usb.lite.STOP"

        private val _state = MutableStateFlow(LiteState())
        val state: StateFlow<LiteState> = _state.asStateFlow()

        fun start(context: Context, device: UsbDevice) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, TetherService::class.java).putExtra(UsbManager.EXTRA_DEVICE, device))
        }

        fun stop(context: Context) = context.startService(Intent(context, TetherService::class.java).setAction(ACTION_STOP))

        fun unplugged() = _state.update { it.copy(connected = false, status = "Camera unplugged") }
    }
}
