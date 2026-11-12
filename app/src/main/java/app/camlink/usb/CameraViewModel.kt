package app.camlink.usb

import android.app.Application
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.camlink.usb.ptp.PtpUsb
import app.camlink.usb.ptp.SonyCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase { Disconnected, Connecting, Connected }
enum class FocusState { Idle, Focusing, Focused, Failed }

data class SavedShot(
    val name: String,
    val sizeBytes: Int,
    val thumb: ImageBitmap?,
    /** MediaStore item of the saved original. */
    val uri: android.net.Uri,
) {
    val isJpeg get() = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
}

data class UiState(
    val phase: Phase = Phase.Disconnected,
    val message: String = "Plug the camera into the phone with a USB cable.",
    val model: String = "",
    val focusMode: String = "",
    /** Last tapped AF point as a fraction (0..1) of the live view frame (shown until the camera confirms it). */
    val focusPoint: Pair<Float, Float>? = null,
    /** The camera's own AF area / frame / focus state, live (also when focusing with the camera's buttons). */
    val camFocus: SonyCamera.FocusInfo? = null,
    val focusState: FocusState = FocusState.Idle,
    val busy: String? = null,
    val lastSaved: SavedShot? = null,
    /** Everything saved in this session, newest first (for the in-app viewer). */
    val shots: List<SavedShot> = emptyList(),
    /** Index into [shots] being viewed full screen, or null. */
    val viewing: Int? = null,
    val savedCount: Int = 0,
    /** Mode / aperture / shutter / ISO / EV / WB as reported by the camera. */
    val settings: List<SettingUi> = emptyList(),
    /** Setting whose ‹ › adjuster is open. */
    val selected: Int? = SonyCamera.DPC_F_NUMBER,
    val toast: String? = null,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Serialises USB transactions. Every holder keeps it for one short command only. */
    private val lock = Mutex()
    @Volatile private var camera: SonyCamera? = null
    private var loopJob: Job? = null
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null

    /** Set by the camera's "new image" event so the loop downloads it before asking for more live view. */
    private val objectAdded = AtomicBoolean(false)
    /** Set when the camera reports a focus / AF-area change, so the overlay updates without waiting for the poll. */
    private val focusDirty = AtomicBoolean(false)
    @Volatile private var tapAt = 0L
    @Volatile private var liveViewPausedUntil = 0L
    @Volatile private var shooting = false
    private var awaitingSince = 0L

    // ------------------------------------------------------------------ connection

    fun connect(manager: UsbManager, device: UsbDevice) {
        if (_ui.value.phase != Phase.Disconnected) return
        usbManager = manager; usbDevice = device
        _ui.update { it.copy(phase = Phase.Connecting, message = "Connecting to ${device.productName ?: "camera"}…") }
        DebugLog.clear()
        DebugLog.d("Connecting… (Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MODEL})")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cam = openCamera(manager, device)
                camera = cam
                _ui.update {
                    it.copy(phase = Phase.Connected, model = device.productName ?: "Sony camera",
                        message = "Connected", toast = "Connected to ${device.productName ?: "camera"}")
                }
                applyProps(cam)
                loopJob = launch { runLoop() }
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                DebugLog.d("Connect failed: ${e.javaClass.simpleName}: ${e.message}")
                _ui.update { it.copy(phase = Phase.Disconnected, message = "Could not connect: ${e.message}") }
            }
        }
    }

    private suspend fun openCamera(manager: UsbManager, device: UsbDevice): SonyCamera {
        val cam = SonyCamera(PtpUsb.open(manager, device))
        try { lock.withLock { cam.connect() } } catch (e: Exception) { cam.close(); throw e }
        cam.setEventListener { code, params ->
            if (code == EVENT_PROP_CHANGED && params.isNotEmpty() && params[0] in FOCUS_PROPS) focusDirty.set(true)
            if (code == EVENT_OBJECT_ADDED) {
                // Also fires for shots taken with the camera's own shutter button.
                liveViewPausedUntil = System.currentTimeMillis() + 1500
                objectAdded.set(true)
            }
        }
        return cam
    }

    fun disconnect(reason: String = "Disconnected") {
        val cam = camera ?: return
        camera = null
        loopJob?.cancel()
        _frame.value = null
        _ui.update { it.copy(phase = Phase.Disconnected, message = reason, busy = null, focusPoint = null) }
        viewModelScope.launch(Dispatchers.IO) { lock.withLock { cam.close() } }
    }

    /** Re-opens the USB session in place. Returns false if the camera is really gone. */
    private suspend fun reconnect(): Boolean {
        val m = usbManager ?: return false
        val d = usbDevice ?: return false
        val old = camera ?: return false
        DebugLog.d("Reconnecting…")
        _ui.update { it.copy(busy = "Reconnecting…") }
        lock.withLock { runCatching { old.close() } }
        for (attempt in 1..3) {
            delay(800L * attempt)
            if (camera !== old) return false // user disconnected meanwhile
            try {
                val cam = openCamera(m, d)
                camera = cam
                _ui.update { it.copy(busy = null) }
                DebugLog.d("Reconnected")
                return true
            } catch (e: Exception) {
                DebugLog.d("Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        return false
    }

    // ------------------------------------------------------------------ live loop

    private suspend fun runLoop() {
        var lastProps = 0L
        var failures = 0
        while (viewModelScope.isActive) {
            val cam = camera ?: return
            try {
                val now = System.currentTimeMillis()
                val newImage = objectAdded.getAndSet(false)
                val focusChanged = focusDirty.getAndSet(false)
                if (newImage || focusChanged || now - lastProps > 300) {
                    lastProps = now
                    lock.withLock { cam.refreshProps() }
                    applyProps(cam)
                    if (cam.pendingImages() > 0) downloadPending(cam)
                    else if (awaitingSince > 0 && now - awaitingSince > 40_000) {
                        awaitingSince = 0
                        _ui.update { it.copy(busy = null, toast = "No image received. Check \"Still Img. Save Dest.\" is PC+Camera.") }
                    }
                }
                if (System.currentTimeMillis() < liveViewPausedUntil) {
                    delay(50); continue
                }
                val jpeg = lock.withLock { cam.liveView() }
                if (jpeg != null) {
                    val bmp = BitmapFactory.decodeByteArray(jpeg.first, jpeg.second, jpeg.third)
                    if (bmp != null) _frame.value = bmp.asImageBitmap()
                } else {
                    delay(40)
                }
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loop error", e)
                DebugLog.d("Live loop error: ${e.message}")
                failures++
                // The body stalls the pipe while it writes a picture; clear it and carry on.
                runCatching { lock.withLock { cam.recover() } }
                    .onFailure { DebugLog.d("Recover failed: ${it.message}") }
                if (failures >= 3) {
                    failures = 0
                    if (camera !== cam) return
                    if (!reconnect()) {
                        if (camera === cam) disconnect("Connection lost: ${e.message}")
                        return
                    }
                }
                delay(150)
            }
        }
    }

    private var downloadFailures = 0

    private suspend fun downloadPending(cam: SonyCamera) {
        var guard = 0
        while (cam.pendingImages() > 0 && guard++ < 6) {
            _ui.update { it.copy(busy = "Downloading image…") }
            val img = try {
                lock.withLock {
                    val i = cam.downloadImage()
                    cam.refreshProps()
                    i
                }
            } catch (e: Exception) {
                downloadFailures++
                DebugLog.d("Download failed ($downloadFailures): ${e.message}")
                _ui.update { it.copy(busy = null) }
                if (downloadFailures >= 3) {
                    downloadFailures = 0
                    awaitingSince = 0
                    _ui.update { it.copy(toast = "Couldn't download the photo (it is still on the camera's card)") }
                }
                throw e
            }
            downloadFailures = 0
            _ui.update { it.copy(busy = "Saving ${img.fileName}…") }
            try {
                val uri = withContext(Dispatchers.IO) { PhotoSaver.save(getApplication(), img.fileName, img.bytes) }
                // Optional background FTP upload (no-op when switched off in Settings).
                app.camlink.usb.upload.Uploads.enqueue(getApplication(), uri, img.fileName, img.bytes.size.toLong())
                val thumb = decodeThumb(img)
                val shot = SavedShot(img.fileName, img.bytes.size, thumb ?: previousThumb(), uri)
                _ui.update {
                    it.copy(lastSaved = shot,
                        shots = listOf(shot) + it.shots,
                        viewing = it.viewing?.let { v -> v + 1 }, // keep the open photo in place
                        savedCount = it.savedCount + 1,
                        toast = "Saved ${img.fileName} (${"%.1f".format(img.bytes.size / 1_048_576.0)} MB)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                _ui.update { it.copy(toast = "Saving ${img.fileName} failed: ${e.message}") }
            }
        }
        awaitingSince = 0
        liveViewPausedUntil = 0
        _ui.update { it.copy(busy = null) }
    }

    /** Small preview decoded from the JPEG itself (1/32 scale, cheap). RAW files keep the previous thumbnail. */
    private fun decodeThumb(img: SonyCamera.Image): ImageBitmap? {
        if (!img.fileName.endsWith(".jpg", true) && !img.fileName.endsWith(".jpeg", true)) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 32 }
        return BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, opts)?.asImageBitmap()
    }

    private var lastLogged: Map<Int, Long> = emptyMap()

    private fun applyProps(cam: SonyCamera) {
        val p = cam.props
        if (lastLogged.isNotEmpty()) {
            for ((k, v) in p) {
                val old = lastLogged[k]
                if (old != null && old != v && k !in NOISY_PROPS) DebugLog.d("Prop %04X: 0x%X -> 0x%X".format(k, old, v))
            }
        }
        lastLogged = p
        val fm = p[SonyCamera.DPC_FOCUS_MODE]?.takeIf { it != 0L }?.let { Exposure.focusMode(it) } ?: ""
        val settings = Exposure.build(cam.propInfo)
        val focus = cam.focusInfo()
        val s = _ui.value
        // Drop the tapped marker once the camera reports the new spot position (or after 2.5 s).
        val tap = s.focusPoint?.takeIf { tp ->
            val confirmed = focus?.spot?.let { sp -> kotlin.math.abs(sp.first - tp.first) < 0.03f && kotlin.math.abs(sp.second - tp.second) < 0.03f } == true
            !confirmed && System.currentTimeMillis() - tapAt < 2500
        }
        if (s.focusMode != fm || s.settings != settings || s.camFocus != focus || s.focusPoint != tap)
            _ui.update { it.copy(focusMode = fm, settings = settings, camFocus = focus, focusPoint = tap) }
    }

    // ------------------------------------------------------------------ actions

    private fun action(block: suspend (SonyCamera) -> Unit) {
        val cam = camera ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { block(cam) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "action failed", e)
                DebugLog.d("Action failed: ${e.message}")
                runCatching { lock.withLock { cam.recover() } }
                _ui.update { it.copy(toast = "Camera didn't respond, try again", busy = null) }
            }
        }
    }

    /**
     * Half-presses and polls for focus lock, holding the USB lock only for each short command,
     * so live view frames keep flowing while the lens moves.
     */
    private suspend fun waitForFocus(cam: SonyCamera, maxMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxMs) {
            delay(80)
            lock.withLock { cam.refreshProps() }
            applyProps(cam)
            // Ignore the first ~150 ms: the previous lock state can still be reported.
            if (System.currentTimeMillis() - start > 150 && cam.isFocusLocked()) return true
        }
        return false
    }

    /** Tap-to-focus: [fx], [fy] are fractions (0..1) of the live view frame. */
    fun focusAt(fx: Float, fy: Float) = action { cam ->
        if (shooting) return@action
        tapAt = System.currentTimeMillis()
        _ui.update { it.copy(focusPoint = fx to fy, focusState = FocusState.Focusing) }
        if (cam.isManualFocus()) {
            _ui.update { it.copy(focusState = FocusState.Idle, toast = "Camera is in MF – use the Near/Far buttons, or switch to AF.") }
            return@action
        }
        lock.withLock {
            cam.setAfPoint(fx, fy)
            cam.halfPress(true)
        }
        val ok = try { waitForFocus(cam, 3000) } finally { lock.withLock { cam.halfPress(false) } }
        showFocusResult(ok)
    }

    fun autoFocus() = action { cam ->
        if (shooting || cam.isManualFocus()) return@action
        _ui.update { it.copy(focusState = FocusState.Focusing) }
        lock.withLock { cam.halfPress(true) }
        val ok = try { waitForFocus(cam, 3000) } finally { lock.withLock { cam.halfPress(false) } }
        showFocusResult(ok)
    }

    /** The camera drops back to "idle" on release; keep the result visible for a moment. */
    private fun showFocusResult(ok: Boolean) {
        val result = if (ok) FocusState.Focused else FocusState.Failed
        _ui.update { it.copy(focusState = result) }
        viewModelScope.launch {
            delay(1800)
            _ui.update { if (it.focusState == result) it.copy(focusState = FocusState.Idle) else it }
        }
    }

    /** Picks an exact value for a list setting (WB, focus mode, AF area) in one tap. */
    fun setOption(code: Int, value: Long) = action { cam ->
        lock.withLock { cam.setValue(code, value) }
        repeat(10) {
            delay(150)
            lock.withLock { cam.refreshProps() }
            applyProps(cam)
            if (cam.props[code] == value) return@action
        }
    }

    fun select(code: Int) = _ui.update { it.copy(selected = if (it.selected == code) null else code) }

    /**
     * ‹ / › on a setting. Dials (aperture, shutter, ISO, EV) move one notch; white balance moves
     * to the neighbouring entry of the camera's own list. The camera needs ~0.5–1 s to apply it.
     */
    fun adjust(code: Int, direction: Int) = action { cam ->
        if (Exposure.isList(code)) {
            val info = cam.propInfo[code] ?: return@action
            val list = info.enumValues ?: return@action
            val idx = list.indexOf(info.current).coerceAtLeast(0)
            val next = list[(idx + direction).mod(list.size)]
            lock.withLock { cam.setValue(code, next) }
        } else {
            lock.withLock { cam.step(code, direction) }
        }
        // Refresh quickly so the new value shows without waiting for the next poll.
        repeat(if (code == SonyCamera.DPC_FOCUS_MODE) 10 else 6) {
            delay(150)
            lock.withLock { cam.refreshProps() }
            applyProps(cam)
        }
    }

    /** Half-press, wait for focus (live view keeps running), full-press, release. */
    fun shoot() = action { cam ->
        if (shooting || _ui.value.busy != null) return@action
        shooting = true
        try {
            _ui.update { it.copy(busy = "Focusing…") }
            lock.withLock { cam.halfPress(true) }
            val focused = if (cam.isManualFocus()) true else waitForFocus(cam, 3000)
            DebugLog.d("Shoot: focus ${if (focused) "locked" else "not confirmed"}")
            _ui.update { it.copy(busy = "Shooting…") }
            // The body stalls live view requests while exposing and writing; pause them briefly.
            liveViewPausedUntil = System.currentTimeMillis() + 2500
            lock.withLock { cam.fullPress(true) }
            delay(300)
            lock.withLock { cam.fullPress(false); cam.halfPress(false) }
            awaitingSince = System.currentTimeMillis()
            _ui.update { it.copy(busy = "Waiting for image…") }
        } finally {
            shooting = false
        }
    }

    fun toastShown() = _ui.update { it.copy(toast = null) }

    private fun previousThumb() = _ui.value.lastSaved?.thumb

    /** Opens the full-screen viewer at [index] of [UiState.shots] (null closes it). */
    fun view(index: Int?) = _ui.update { it.copy(viewing = index?.takeIf { i -> i in it.shots.indices }) }

    override fun onCleared() {
        camera?.let { cam -> camera = null; runCatching { cam.close() } }
    }

    companion object {
        private const val TAG = "CamLinkUSB"
        private const val EVENT_OBJECT_ADDED = 0xC201
        private const val EVENT_PROP_CHANGED = 0xC203
        /** Change constantly (live metering); not logged. */
        private val NOISY_PROPS = setOf(0xD21D, 0x5004)
        private val FOCUS_PROPS = setOf(SonyCamera.DPC_FOCUS_FOUND, SonyCamera.DPC_FOCUS_AREA, SonyCamera.DPC_FOCUS_POS_SET)
    }
}
