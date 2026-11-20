package com.example.clawd

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import android.view.*

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

    private fun r(c: Canvas, x: Float, y: Float, w: Float, h: Float) =
        c.drawRect(x, y, x + w, y + h, p)

    override fun onDraw(c: Canvas) {
        for (i in GRID.indices) for (j in 0 until 18)
            if (GRID[i][j] == '#') r(c, j * s, i * s, s, s)
        val base = intArrayOf(2, 6, 10, 14)
        for (k in 0 until 4) {
            val l = base[k] + if (leg == 1) (if (k % 2 == 0) -1 else 1) else 0
            r(c, l * s, 10 * s, 2 * s, 2 * s)
        }
    }
}

class ClawdService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var view: ClawdView
    private lateinit var lp: WindowManager.LayoutParams
    private val h = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var dir = 1
    private var t = 0
    private var sw = 0
    private var sh = 0
    private var sz = 0
    private var hh = 0
    private var spd = 0f
    private var ox = 0f
    private var oy = 0f
    private var drag = false

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
        sw = dm.widthPixels; sh = dm.heightPixels
        val s = dm.density * 3f
        sz = (18 * s).toInt(); hh = (12 * s).toInt()
        spd = dm.density * 0.9f
        x = sw * 0.3f
        y = (sh - hh - 96 * dm.density)

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
                MotionEvent.ACTION_DOWN -> { drag = true; ox = e.rawX - x; oy = e.rawY - y }
                MotionEvent.ACTION_MOVE -> { x = e.rawX - ox; y = e.rawY - oy; place() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = false
            }
            true
        }

        wm.addView(view, lp)
        place()
        h.post(tick)
    }

    private fun step() {
        if (!drag) {
            x += dir * spd
            if (x <= 0f) { x = 0f; dir = 1 }
            if (x >= sw - sz) { x = (sw - sz).toFloat(); dir = -1 }
        }
        t++
        view.leg = (t / 10) % 2
        view.invalidate()
        place()
    }

    private fun place() {
        x = x.coerceIn(0f, (sw - sz).toFloat())
        y = y.coerceIn(0f, (sh - hh).toFloat())
        lp.x = x.toInt(); lp.y = y.toInt()
        wm.updateViewLayout(view, lp)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        wm.removeView(view)
        super.onDestroy()
    }
}
