package my.fouriders.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground MediaProjection service. Android displays its own capture-consent dialog
 * before this service starts. OCR is throttled and duplicate text is suppressed.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var lastCaptureAt = 0L
    private var lastText = ""
    private val processing = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != 0 && data != null) beginCapture(resultCode, data)
        else stopWithState(false, "Screen capture permission missing")
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            worker = HandlerThread("4RidersOCR").also { it.start() }
            val handler = Handler(worker!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ source ->
                val now = System.currentTimeMillis()
                if (now - lastCaptureAt < 900 || !processing.compareAndSet(false, true)) return@setOnImageAvailableListener
                lastCaptureAt = now
                val image = source.acquireLatestImage() ?: run { processing.set(false); return@setOnImageAvailableListener }
                try {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    val input = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(input)
                        .addOnSuccessListener { result ->
                            val text = result.text.trim()
                            if (text.length >= 12 && text != lastText) {
                                lastText = text
                                sendBroadcast(Intent(ACTION_OCR_TEXT).setPackage(packageName).putExtra(EXTRA_OCR_TEXT, text))
                            }
                        }
                        .addOnCompleteListener { bitmap.recycle(); processing.set(false) }
                } catch (_: Exception) {
                    if (!image.isClosed) image.close()
                    processing.set(false)
                }
            }, handler)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopWithState(false, "Screen capture stopped by Android") }
            }, handler)
            display = projection!!.createVirtualDisplay("4RidersScreen", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            sendState(true, "Screen capture and OCR active")
        } catch (error: Exception) {
            stopWithState(false, error.message ?: "Unable to start screen capture")
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val paddedWidth = image.width + (plane.rowStride - pixelStride * image.width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun stopWithState(active: Boolean, detail: String) {
        sendState(active, detail)
        stopSelf()
    }

    private fun sendState(active: Boolean, detail: String) {
        sendBroadcast(Intent(ACTION_CAPTURE_STATE).setPackage(packageName)
            .putExtra(EXTRA_ACTIVE, active).putExtra(EXTRA_DETAIL, detail))
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        worker?.quitSafely()
        recognizer.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "4Riders screen scanner", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("4Riders screen scanner")
            .setContentText("Reading visible Grab offer details")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    }

    companion object {
        const val ACTION_OCR_TEXT = "my.fouriders.scan.OCR_TEXT"
        const val ACTION_CAPTURE_STATE = "my.fouriders.scan.CAPTURE_STATE"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "fouriders_screen_capture"
        private const val NOTIFICATION_ID = 415
    }
}
