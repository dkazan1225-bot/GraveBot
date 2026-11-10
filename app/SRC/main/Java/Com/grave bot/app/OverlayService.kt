package com.gravebot.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*

class OverlayService : Service() {
    companion object {
        @Volatile private var active: OverlayService? = null
        fun update(result: CellTracker.Result) {
            Handler(Looper.getMainLooper()).post { active?.render(result) }
        }
    }
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private val cells = ArrayList<TextView>()

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        active = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 10)
            setBackgroundColor(0xEE202020.toInt())
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "GraveBot 1.0 · трекер"; textSize = 14f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Button(this).apply { text = "×"; setOnClickListener { stopSelf() } })
        panel.addView(head)
        status = TextView(this).apply {
            text = "Жду кадры игры…"; textSize = 11f
            setTextColor(0xFFFFD180.toInt()); setPadding(0, 4, 0, 6)
        }
        panel.addView(status)
        val grid = GridLayout(this).apply { columnCount = 5; rowCount = 5 }
        for (i in 0 until 25) {
            val cell = TextView(this).apply {
                text = "${i/5+1}.${i%5+1}"; gravity = Gravity.CENTER
                textSize = 12f; setTextColor(Color.WHITE)
                setBackgroundColor(0xFF555555.toInt()); setPadding(8, 10, 8, 10)
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0; height = 40
                columnSpec = GridLayout.spec(i%5, 1f)
                rowSpec = GridLayout.spec(i/5)
                setMargins(2, 2, 2, 2)
            }
            grid.addView(cell, lp); cells.add(cell)
        }
        panel.addView(grid)
        panel.addView(TextView(this).apply {
            text = "Оранжевый = отслеживаемая цель · серый = нет уверенного совпадения"
            textSize = 10f; setTextColor(Color.LTGRAY); setPadding(0, 6, 0, 0)
        })
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 120 }
        root = panel
        wm.addView(panel, params)
    }

    private fun render(result: CellTracker.Result) {
        if (cells.size != 25) return
        for (i in 0 until 25) {
            cells[i].setBackgroundColor(when (result.states[i]) {
                1 -> 0xFFE67E22.toInt()
                0 -> 0xFF27834A.toInt()
                else -> 0xFF555555.toInt()
            })
        }
        status?.text = result.phase + " · тыкв в кадре: ${result.pumpkinCount}"
    }

    override fun onDestroy() {
        active = null
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        cells.clear()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
