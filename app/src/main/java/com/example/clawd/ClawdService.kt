package com.example.clawd

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.TextView
import java.util.Calendar
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

class ClawdView(ctx: Context, private val s: Int) : View(ctx) {
    private val p = Paint().apply { color = Color.rgb(0xd9, 0x77, 0x57) }
    private val dark = Paint().apply { color = Color.rgb(0x2a, 0x14, 0x0c) }
    var leg = 0
    var sleep = false

    private fun r(c: Canvas, x: Int, y: Int, w: Int, h: Int, pt: Paint) =
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), pt)

    override fun onDraw(c: Canvas) {
        for (i in GRID.indices) for (j in 0 until 18) {
            val ch = GRID[i][j]
            if (ch == '#') r(c, j * s, i * s, s, s, p)
            else if (sleep && (i == 2 || i == 3) && (j == 5 || j == 12)) r(c, j * s, i * s, s, s, p)
        }
        if (sleep) {
            for (j in intArrayOf(5, 12)) r(c, j * s, 3 * s + s / 2, s, max(1, s / 4), dark)
        }
        val base = intArrayOf(2, 6, 10, 14)
        for (k in 0 until 4) {
            val l = base[k] + if (leg == 1) (if (k % 2 == 0) -1 else 1) else 0
            r(c, l * s, 10 * s, 2 * s, 2 * s, p)
        }
    }
}

class ClawdService : Service() {
    private class P(val t: Long, val x: Float, val y: Float)

    private lateinit var wm: WindowManager
    private lateinit var view: ClawdView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var zView: TextView
    private lateinit var zlp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private val hist = ArrayList<P>()
    private var created = false
    private var alive = false

    private var k = 1f
    private var s = 3
    private var G = 0f
    private var WALK = 0f
    private var sw = 0
    private var sh = 0
    private var sz = 0
    private var hh = 0
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var dir = 1f
    private var mode = 1          // 0 ходьба, 1 полет/падение, 2 лазание
    private var wall = 0
    private var t = 0
    private var pause = 0
    private var drag = false
    private var ox = 0f
    private var oy = 0f
    private var asleep = false
    private var floorOff = 0f     // поднять пол, если клодик уходит под панель навигации

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            try {
                step()
            } catch (e: Exception) {
                Log.e("Clawd", "step failed", e)
            }
            h.postDelayed(this, 16)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    // Система перезапустит сервис после убийства
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        if (created) return
        created = true
        alive = true

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("c", "Clawd", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "c")
            .setContentTitle("Clawd")
            .setContentText("Клодик на экране")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        k = dm.density
        s = max(2, (k * 3f).roundToInt())   // целый размер клетки, без швов
        sz = 18 * s
        hh = 12 * s
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

        zView = TextView(this).apply {
            text = "Z"
            setTextColor(Color.rgb(0xd8, 0xd8, 0xe8))
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * k)
            visibility = View.GONE
        }
        zlp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
        wm.addView(zView, zlp)
        place()
        h.post(tick)
    }

    private fun groundY(): Float = sh - hh - floorOff

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

    private fun isNight(): Boolean {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hr >= 23 || hr < 8
    }

    private fun startSleep() {
        if (mode == 2) { mode = 1; vx = 0f; vy = 0f }
        else if (mode == 0) { mode = 1; vx = 0f; vy = -5.5f * k }  // прыжок перед сном
    }

    private fun step() {
        t++
        val night = isNight()
        if (night && !asleep) {
            asleep = true
            if (!drag) startSleep()
        } else if (!night && asleep) {
            asleep = false
        }

        if (!drag) physics()

        view.sleep = asleep
        view.leg = when {
            asleep -> 0
            mode == 1 -> 1
            mode == 0 && pause == 0 && (t / 10) % 2 == 1 -> 1
            else -> 0
        }
        view.invalidate()

        val showZ = asleep && !drag && (t % 150) < 100
        zView.visibility = if (showZ) View.VISIBLE else View.GONE
        place()
    }

    private fun physics() {
        val gy = groundY()
        val maxX = (sw - sz).toFloat()
        when (mode) {
            0 -> { // ходьба по полу
                if (!asleep) {
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
                }
                if (mode == 0) y = gy
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
            else -> { // падение и полет
                vy += G
                x += vx
                y += vy
                if (x < 0f) { x = 0f; vx = abs(vx) * 0.6f }
                if (x > maxX) { x = maxX; vx = -abs(vx) * 0.6f }
                if (y < 0f) { y = 0f; vy = abs(vy) * 0.4f }
                if (y >= gy) {
                    y = gy
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

        // Z над головой, плавно поднимается
        val zOff = (t % 150) * 0.1f * k
        zlp.x = (x + sz * 0.7f).roundToInt()
        zlp.y = (y - s * 2 - zOff).roundToInt()
        wm.updateViewLayout(zView, zlp)
    }

    override fun onDestroy() {
        alive = false
        h.removeCallbacks(tick)
        try { wm.removeView(view) } catch (e: Exception) { Log.e("Clawd", "remove view", e) }
        try { wm.removeView(zView) } catch (e: Exception) { Log.e("Clawd", "remove z", e) }
        created = false
        super.onDestroy()
    }
}
