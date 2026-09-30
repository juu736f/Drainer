package dev.drain

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var sw: Switch
    private lateinit var info: TextView
    private lateinit var balls: BallsView
    private val h = Handler(Looper.getMainLooper())
    private var last = SystemClock.elapsedRealtime()
    private var sync = false
    private val core = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
    )
    private val opt = arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)
    private lateinit var col: LinearLayout
    private var ticks = 0

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        sw = Switch(this).apply {
            text = "STRESS TEST"; textSize = 28f; setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on -> if (!sync) toggle(on) }
        }
        info = TextView(this).apply {
            setTextColor(Color.GREEN); typeface = Typeface.MONOSPACE; textSize = 16f; setPadding(0, 48, 0, 0)
        }
        balls = BallsView(this).apply { visibility = View.GONE }
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 120, 48, 48); addView(sw); addView(info)
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(col); addView(balls) })
    }

    override fun onResume() {
        super.onResume()
        last = SystemClock.elapsedRealtime()
        sync = true; sw.isChecked = State.running; sync = false
        screen(State.running)
        tick()
    }

    override fun onPause() { h.removeCallbacksAndMessages(null); super.onPause() }

    private fun tick() {
        info.text = "STATE  %s\nCPU    %d primes | last %d\nGPS    %s\nWIFI   %d APs\nBT     %d devices\nCELL   %d towers".format(
            if (State.running) "ON" else "OFF", State.primes.get(), State.lastPrime,
            if (State.lat.isNaN()) "searching..." else "%.6f, %.6f (±%.0f m)".format(State.lat, State.lon, State.acc),
            State.wifi, State.bt, State.cells
        )
        if (State.running && balls.visibility != View.VISIBLE && SystemClock.elapsedRealtime() - last >= 300_000)
            balls.visibility = View.VISIBLE
        if (++ticks % 120 == 0) { col.translationX = Random.nextInt(-40, 41).toFloat(); col.translationY = Random.nextInt(-40, 41).toFloat() }
        h.postDelayed(::tick, 500)
    }

    private fun toggle(on: Boolean) {
        if (!on) { stopService(Intent(this, DrainService::class.java)); screen(false); return }
        val all = arrayOf(*core, *opt)
        if (all.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) begin()
        else requestPermissions(all, 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (core.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) begin()
        else { sync = true; sw.isChecked = false; sync = false }
    }

    private fun begin() { startForegroundService(Intent(this, DrainService::class.java)); screen(true) }

    private fun screen(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.insetsController?.let {
            if (on) { it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; it.hide(WindowInsets.Type.systemBars()) }
            else it.show(WindowInsets.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) screen(State.running) }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        last = SystemClock.elapsedRealtime()
        if (balls.visibility == View.VISIBLE) {
            if (e.action == MotionEvent.ACTION_DOWN) balls.visibility = View.GONE
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}

class BallsView(c: Context) : View(c) {
    private class Ball(var x: Float, var y: Float, var vx: Float, var vy: Float, val r: Float, val color: Int)
    private val balls = mutableListOf<Ball>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noise = Paint().apply {
        val px = IntArray(256 * 256) { val g = Random.nextInt(256); Color.argb(Random.nextInt(0, 36), g, g, g) }
        shader = BitmapShader(Bitmap.createBitmap(px, 256, 256, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private var t0 = 0L

    init { setBackgroundColor(Color.BLACK) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        balls.clear()
        repeat(40) {
            balls += Ball(
                Random.nextFloat() * w, Random.nextFloat() * h,
                (Random.nextFloat() - .5f) * 4000f, (Random.nextFloat() - .5f) * 4000f,
                22f + Random.nextFloat() * 40f, Color.HSVToColor(floatArrayOf(Random.nextFloat() * 360f, 1f, 1f))
            )
        }
    }

    override fun onVisibilityChanged(v: View, vis: Int) {
        if (vis == VISIBLE) { t0 = 0; postInvalidateOnAnimation() }
    }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        val dt = if (t0 == 0L) 0f else (now - t0) / 1e9f
        t0 = now
        for (b in balls) {
            b.x += b.vx * dt; b.y += b.vy * dt
            if (b.x < b.r) { b.x = b.r; b.vx = abs(b.vx) } else if (b.x > width - b.r) { b.x = width - b.r; b.vx = -abs(b.vx) }
            if (b.y < b.r) { b.y = b.r; b.vy = abs(b.vy) } else if (b.y > height - b.r) { b.y = height - b.r; b.vy = -abs(b.vy) }
            p.color = b.color
            cv.drawCircle(b.x, b.y, b.r, p)
        }
        cv.save()
        cv.translate(-Random.nextInt(256).toFloat(), -Random.nextInt(256).toFloat())
        cv.drawRect(0f, 0f, width + 256f, height + 256f, noise)
        cv.restore()
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
