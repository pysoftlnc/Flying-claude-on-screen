package com.example.clawd

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import android.view.*
import kotlin.math.*

val GRID = arrayOf(
    "..##############..",
    "..##############..",
    "..###.######.###..",
    "..###.######.###..",
    "##################",
    "##################",
    "..##############..",
    "..##############..",
    "..##############..",
    "..##############..",
)

class ClawdView(ctx: Context, private val s: Float) : View(ctx) {
    private val p = Paint().apply { color = Color.rgb(0xd9, 0x77, 0x57) }
    var leg = 0

    override fun onDraw(c: Canvas) {
        for (i in GRID.indices) for (j in 0 until 18)
            if (GRID[i][j] == '#') c.drawRect(j * s, i * s, (j + 1) * s, (i + 1) * s, p)
        val base = intArrayOf(2, 6, 10, 14)
        for (k in 0 until 4) {
            val l = base[k] + if (leg == 1) (if (k % 2 == 0) -1 else 1) else 0
            c.drawRect(l * s, 10 * s, (l + 2) * s, 12 * s, p)
        }
    }
}

class ClawdService : Service() {
    private class P(val t: Long, val x: Float, val y: Float)

    private lateinit var wm: WindowManager
    private lateinit var view: ClawdView
    private lateinit var lp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private val hist = ArrayList<P>()

    private var k = 1f            // плотность экрана (px на 1 dp)
    private var G = 0f            // гравитация
    private var WALK = 0f         // скорость ходьбы
    private var sw = 0
    private var sh = 0
    private var sz = 0
    private var hh = 0
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var dir = 1f
    private var mode = 1          // 0 ходьба, 1 падение/полет, 2 лазание
    private var wall = 0
    private var t = 0
    private var pause = 0
    private var drag = false
    private var ox = 0f
    private var oy = 0f
    private var floorOff = 0f     // поднять пол, если клодик уходит под панель навигации

    private val tick = object : Runnable {
        override fun run() { step(); h.postDelayed(this, 16) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("c", "Clawd", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "c")
            .setContentTitle("Clawd")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        k = dm.density
        val s = k * 3f
        sz = (18 * s).toInt()
        hh = (12 * s).toInt()
        G = 0.3f * k
        WALK = 0.9f * k

        x = sw * 0.3f
        y = 0f
        dir = if (Math.random() < 0.5) -1f else 1f

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = ClawdView(this, s)
        lp = WindowManager.LayoutParams(
            sz, hh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drag = true
                    ox = e.rawX - x
                    oy = e.rawY - y
                    vx = 0f; vy = 0f
                    mode = 1
                    hist.clear()
                    hist.add(P(SystemClock.uptimeMillis(), x, y))
                }
                MotionEvent.ACTION_MOVE -> {
                    x = e.rawX - ox
                    y = e.rawY - oy
                    val now = SystemClock.uptimeMillis()
                    hist.add(P(now, x, y))
                    hist.removeAll { now - it.t > 100 }
                    place()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            true
        }

        wm.addView(view, lp)
        place()
        h.post(tick)
    }

    private fun floor(): Float = sh - hh - floorOff

    // Бросок: скорость по последним точкам движения пальца
    private fun release() {
        if (!drag) return
        drag = false
        val now = SystemClock.uptimeMillis()
        hist.removeAll { now - it.t > 100 }
        if (hist.size > 1) {
            val a = hist.first()
            val b = hist.last()
            val dt = max(1L, b.t - a.t).toFloat()
            vx = (b.x - a.x) / dt * 16f * 0.7f
            vy = (b.y - a.y) / dt * 16f * 0.7f
        }
        val lim = 40f * k
        vx = vx.coerceIn(-lim, lim)
        vy = vy.coerceIn(-lim, lim)
        mode = 1
    }

    private fun step() {
        t++
        if (!drag) physics()
        view.leg = if (mode == 1 || (pause == 0 && (t / 10) % 2 == 1)) 1 else 0
        view.invalidate()
        place()
    }

    private fun physics() {
        val fl = floor()
        val maxX = (sw - sz).toFloat()
        when (mode) {
            0 -> { // ходьба по полу
                if (pause > 0) pause--
                else {
                    x += dir * WALK
                    val r = Math.random()
                    if (r < 0.004) pause = (40 + Math.random() * 80).toInt()
                    else if (r < 0.006) dir = -dir
                    else if (r < 0.0065) { // маленький прыжок
                        mode = 1
                        vy = -5.5f * k
                        vx = dir * WALK * 1.5f
                    }
                }
                if (x <= 0f) { x = 0f; mode = 2; wall = -1 }
                if (x >= maxX) { x = maxX; mode = 2; wall = 1 }
                if (mode == 0) y = fl
            }
            2 -> { // лазание по стене вверх
                y -= 0.8f * k
                if (y <= 0f) {
                    y = 0f
                    mode = 1
                    vx = -wall * 1.5f * k
                    vy = 0f
                }
            }
            else -> { // падение и полет после броска
                vy += G
                x += vx
                y += vy
                if (x < 0f) { x = 0f; vx = abs(vx) * 0.6f }
                if (x > maxX) { x = maxX; vx = -abs(vx) * 0.6f }
                if (y < 0f) { y = 0f; vy = abs(vy) * 0.4f }
                if (y >= fl) {
                    y = fl
                    if (vy > 2f * k) vy = -vy * 0.35f   // отскок
                    else {
                        vy = 0f
                        vx *= 0.9f                       // трение
                        if (abs(vx) < 0.2f * k) {
                            vx = 0f
                            mode = 0
                        }
                    }
                }
            }
        }
    }

    private fun place() {
        x = x.coerceIn(0f, (sw - sz).toFloat())
        y = y.coerceIn(0f, (sh - hh).toFloat())
        lp.x = x.roundToInt()
        lp.y = y.roundToInt()
        wm.updateViewLayout(view, lp)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        wm.removeView(view)
        super.onDestroy()
    }
}
