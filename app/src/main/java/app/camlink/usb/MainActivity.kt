package app.camlink.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import app.camlink.usb.ptp.PtpUsb
import app.camlink.usb.ptp.SonyCamera
import app.camlink.usb.ui.CameraScreen

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()
    private val usb by lazy { getSystemService(USB_SERVICE) as UsbManager }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) vm.connect(usb, device)
                    else Toast.makeText(context, "USB permission denied", Toast.LENGTH_SHORT).show()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> vm.disconnect("Camera unplugged")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> tryConnect(device)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent { CameraScreen(vm, onConnect = { scanAndConnect() }) }

        intent?.usbDevice()?.let { tryConnect(it) } ?: scanAndConnect(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.usbDevice()?.let { tryConnect(it) }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
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
        if (usb.hasPermission(device)) {
            vm.connect(usb, device)
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
