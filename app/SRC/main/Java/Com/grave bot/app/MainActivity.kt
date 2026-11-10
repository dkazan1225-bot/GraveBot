package com.gravebot.app

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.*

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var leftField: EditText
    private lateinit var topField: EditText
    private lateinit var widthField: EditText
    private lateinit var heightField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("board", MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF5F1EA.toInt())
        }
        fun label(s: String, n: Float = 15f) = TextView(this).apply {
            text = s; textSize = n; setTextColor(0xFF29231F.toInt()); setPadding(0, 9, 0, 9)
        }
        fun field(caption: String, value: Int): EditText {
            box.addView(label(caption, 13f))
            return EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(value.toString())
                hint = "проценты"
                box.addView(this)
            }
        }
        box.addView(label("GraveBot", 30f))
        box.addView(label("Android 14 · отслеживание 5 целей на поле 5×5", 17f))
        box.addView(label("Укажи прямоугольник игрового поля на всём экране. Координаты задаются в процентах: X и Y — левый верхний угол, W и H — ширина и высота. Значения по умолчанию приблизительные; их нужно подогнать под расположение поля."))
        leftField = field("X — отступ слева (%)", prefs.getInt("left", 7))
        topField = field("Y — отступ сверху (%)", prefs.getInt("top", 25))
        widthField = field("W — ширина поля (%)", prefs.getInt("width", 86))
        heightField = field("H — высота поля (%)", prefs.getInt("height", 37))
        box.addView(Button(this).apply {
            text = "Сохранить область поля"
            setOnClickListener {
                fun num(e: EditText, fallback: Int) = e.text.toString().toIntOrNull()?.coerceIn(1,100) ?: fallback
                prefs.edit()
                    .putInt("left", num(leftField,7)).putInt("top",num(topField,25))
                    .putInt("width",num(widthField,86)).putInt("height",num(heightField,37)).apply()
                Toast.makeText(this@MainActivity, "Область сохранена. Перезапусти захват экрана.", Toast.LENGTH_LONG).show()
            }
        })
        box.addView(Button(this).apply {
            text = "Разрешить панель поверх игры"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        })
        box.addView(Button(this).apply {
            text = "Показать панель"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) startService(Intent(this@MainActivity, OverlayService::class.java))
                else Toast.makeText(this@MainActivity, "Сначала разреши показ поверх других приложений", Toast.LENGTH_LONG).show()
            }
        })
        box.addView(Button(this).apply {
            text = "Начать захват и анализ"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 72)
                }
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
            }
        })
        box.addView(Button(this).apply {
            text = "Остановить захват"
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenCaptureService::class.java)) }
        })
        box.addView(Button(this).apply {
            text = "Скрыть панель"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        box.addView(label("Важно: трекер экспериментальный. Он запоминает 5 надгробий, затем сопоставляет локальные фрагменты изображения между кадрами. При потере уверенности клетка остаётся серой. Зелёные безопасные клетки не показываются, поскольку безопасность остальных клеток пока не подтверждена."))
        setContentView(ScrollView(this).apply { addView(box) })
    }

    @Deprecated("Deprecated API retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                Toast.makeText(this, "Захват и анализ запущены", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "Захват экрана отменён", Toast.LENGTH_SHORT).show()
        }
    }
}
