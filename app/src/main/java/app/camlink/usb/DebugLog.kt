package app.camlink.usb

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown on screen, so connection problems can be diagnosed without a PC. */
object DebugLog {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    @Synchronized
    fun d(msg: String) {
        Log.d("CamLinkUSB", msg)
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
        _text.value = lines.joinToString("\n")
    }

    @Synchronized
    fun clear() { lines.clear(); _text.value = "" }
}
