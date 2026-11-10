package com.gravebot.app

import android.app.*
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureService: Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL = "gravebot_capture"
        private const val NOTIFICATION_ID = 51
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var lastAnalysis = 0L
    private var tracker: CellTracker? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("GraveBot")
            .setContentText("Отслеживание объектов на поле 5×5")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.parcelableIntent(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val prefs = getSharedPreferences("board", Context.MODE_PRIVATE)
        tracker = CellTracker(
            prefs.getInt("left", 7) / 100f,
            prefs.getInt("top", 25) / 100f,
            prefs.getInt("width", 86) / 100f,
            prefs.getInt("height", 37) / 100f
        )
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(mainLooper))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi
        worker = HandlerThread("GraveBotVision").apply { start() }
        val handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastAnalysis >= 33L) {
                    lastAnalysis = now
                    val bitmap = CellTracker.fromImage(image)
                    if (bitmap != null) {
                        val result = tracker?.analyze(bitmap)
                        bitmap.recycle()
                        if (result != null) OverlayService.update(result)
                    }
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)
        display = projection?.createVirtualDisplay(
            "GraveBotCapture", w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null
        )
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Захват экрана GraveBot", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    override fun onDestroy() {
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        projection?.stop(); projection = null
        worker?.quitSafely(); worker = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
@Suppress("DEPRECATION")
private fun Intent.parcelableIntent(key: String): Intent? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else getParcelableExtra(key)
