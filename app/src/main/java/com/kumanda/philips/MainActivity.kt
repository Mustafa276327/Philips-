package com.kumanda.philips

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Philips RC-6 (mod 0, adres 0, 36 kHz) komutları. Tuşlar için IR kodu yoksa listede yoktur. */
private val CODES: Map<String, Int> = mapOf(
    "power" to 0x0C, "mute" to 0x0D, "info" to 0x0F,
    "vu" to 0x10, "vd" to 0x11, "cu" to 0x20, "cd" to 0x21,
    "up" to 0x58, "down" to 0x59, "left" to 0x5A, "right" to 0x5B, "ok" to 0x5C,
    "home" to 0x54,
    "red" to 0x6D, "green" to 0x6E, "yellow" to 0x6F, "blue" to 0x70,
    // Aşağıdakiler yaygın Philips kodlarıdır; TV'nizde tepki vermezse değerleri değiştirin.
    "back" to 0x0A, "src" to 0x38, "txt" to 0x3C, "menu" to 0x40,
    "rew" to 0x2B, "play" to 0x2C, "ff" to 0x28, "pause" to 0x30, "stop" to 0x31
) + (0..9).associate { "d$it" to it }

private val REPEATING = setOf("vu", "vd", "cu", "cd", "up", "down", "left", "right")

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var toggle = 0
    private var repeater: Runnable? = null
    private lateinit var status: TextView
    private val ir: ConsumerIrManager? by lazy {
        getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }

    // ---------- IR ----------

    /** RC-6 darbe dizisi (mikrosaniye): açık, kapalı, açık, ... */
    private fun rc6(cmd: Int, tg: Int, addr: Int = 0): IntArray {
        val levels = ArrayList<Int>()
        fun add(v: Int, n: Int) { repeat(n) { levels.add(v) } }
        fun bit(b: Int, w: Int = 1) { add(b, w); add(1 - b, w) }
        add(1, 6); add(0, 2)                 // lider
        bit(1); bit(0); bit(0); bit(0)       // başlangıç biti + mod 0
        bit(tg, 2)                           // toggle biti (çift genişlik)
        for (i in 7 downTo 0) bit((addr shr i) and 1)
        for (i in 7 downTo 0) bit((cmd shr i) and 1)
        val out = ArrayList<Int>()
        var cur = levels[0]
        var n = 0
        for (v in levels) {
            if (v == cur) n++ else { out.add(n * 444); cur = v; n = 1 }
        }
        out.add(n * 444)
        if (out.size % 2 == 1) out.add(444 * 6)
        return out.toIntArray()
    }

    private fun fire(k: String) {
        window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val c = CODES[k]
        if (c == null) { status.text = "Bu tuş için IR kodu yok"; return }
        val m = ir
        if (m == null || !m.hasIrEmitter()) { status.text = "Bu telefonda IR vericisi yok"; return }
        m.transmit(36000, rc6(c, toggle))
        status.text = "Gönderildi: RC-6 0x%02X".format(c)
    }

    private fun down(k: String) {
        release()
        toggle = toggle xor 1
        fire(k)
        if (k in REPEATING) {
            val r = object : Runnable {
                override fun run() { fire(k); handler.postDelayed(this, 130) }
            }
            repeater = r
            handler.postDelayed(r, 450)
        }
    }

    private fun release() {
        repeater?.let { handler.removeCallbacks(it) }
        repeater = null
    }

    // ---------- Arayüz yardımcıları ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, r: Int, stroke: Int = 0xFF2B2B31.toInt()) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(fill)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun lit(c: Int) = Color.rgb(
        minOf(255, Color.red(c) + 45), minOf(255, Color.green(c) + 45), minOf(255, Color.blue(c) + 45)
    )

    private fun states(fill: Int, press: Int, r: Int, stroke: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(press, r, stroke))
        addState(intArrayOf(), shape(fill, r, stroke))
    }

    private fun touch(v: View, k: String) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { view.isPressed = true; down(k) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { view.isPressed = false; release() }
            }
            true
        }
    }

    private fun tbtn(
        t: String, k: String, fill: Int = 0xFF1C1C20.toInt(), fg: Int = 0xFFE8E8EC.toInt(),
        size: Float = 14f, r: Int = 20, bold: Boolean = false,
        press: Int = lit(fill), stroke: Int = 0xFF2B2B31.toInt()
    ): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(fg)
        v.textSize = size
        v.gravity = Gravity.CENTER
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        v.background = states(fill, press, r, stroke)
        touch(v, k)
        return v
    }

    private fun ibtn(kind: String, k: String, fg: Int = 0xFFE8E8EC.toInt(), r: Int = 20): FrameLayout {
        val f = FrameLayout(this)
        f.background = states(0xFF1C1C20.toInt(), lit(0xFF1C1C20.toInt()), r, 0xFF2B2B31.toInt())
        f.addView(Icon(this, kind, fg), FrameLayout.LayoutParams(-1, -1))
        touch(f, k)
        return f
    }

    private fun cbtn(color: Int, k: String): FrameLayout {
        val f = FrameLayout(this)
        f.background = states(0xFF1C1C20.toInt(), lit(0xFF1C1C20.toInt()), 12, 0xFF2B2B31.toInt())
        val bar = View(this)
        bar.background = shape(color, 2, 0)
        f.addView(bar, FrameLayout.LayoutParams(dp(24), dp(4), Gravity.CENTER))
        touch(f, k)
        return f
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 8f; setTextColor(0xFF8D8D96.toInt()); gravity = Gravity.CENTER
    }

    private fun lab(v: View, t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(v, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(label(t), LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(h: Int, vararg v: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (x in v) l.addView(x, LinearLayout.LayoutParams(0, dp(h), 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        })
        return l
    }

    private fun rocker(upKey: String, downKey: String, kind: String): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.background = shape(0xFF1C1C20.toInt(), 16)
        fun half(t: String, k: String) = tbtn(t, k, fill = 0, size = 24f, r = 16, press = 0x33FFFFFF, stroke = 0)
        l.addView(half("+", upKey), LinearLayout.LayoutParams(-1, 0, 1f))
        l.addView(Icon(this, kind, 0xFFE8E8EC.toInt()), LinearLayout.LayoutParams(-1, dp(36)))
        l.addView(half("−", downKey), LinearLayout.LayoutParams(-1, 0, 1f))
        return l
    }

    // ---------- Ekran ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF1B120D.toInt()

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF2A1D16.toInt())
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.gravity = Gravity.CENTER_HORIZONTAL
        page.setPadding(dp(12), dp(14), dp(12), dp(28))
        sv.addView(page, FrameLayout.LayoutParams(-1, -2))

        status = TextView(this)
        status.setTextColor(0xFFD9C7B8.toInt())
        status.textSize = 12f
        status.gravity = Gravity.CENTER
        status.text = if (ir?.hasIrEmitter() == true) "IR vericisi hazır" else "Bu telefonda IR vericisi bulunamadı"
        page.addView(status, LinearLayout.LayoutParams(-1, -2))

        val rc = LinearLayout(this)
        rc.orientation = LinearLayout.VERTICAL
        rc.gravity = Gravity.CENTER_HORIZONTAL
        rc.background = shape(0xFF0B0B0D.toInt(), 18, 0xFF34343B.toInt())
        rc.setPadding(dp(12), dp(14), dp(12), dp(26))
        val width = minOf(resources.displayMetrics.widthPixels - dp(24), dp(340))
        page.addView(rc, LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(8) })
        val full = { LinearLayout.LayoutParams(-1, -2) }

        // güç + ambilight
        val power = ibtn("pw", "power", 0xFFFF4A3D.toInt(), 26)
        rc.addView(power, LinearLayout.LayoutParams(dp(52), dp(52)).apply { setMargins(0, dp(2), 0, dp(6)) })
        rc.addView(tbtn("AMBILIGHT", "amb", size = 12f, r = 12, bold = true),
            LinearLayout.LayoutParams(dp(150), dp(32)).apply { bottomMargin = dp(4) })

        // uygulama tuşları
        val white = 0xFFE9E5DC.toInt()
        rc.addView(row(40,
            tbtn("prime video", "prime", white, 0xFF0B3A66.toInt(), 13f, 14, true),
            tbtn("YouTube", "yt", white, 0xFFCC0000.toInt(), 13f, 14, true)), full())
        rc.addView(row(40,
            tbtn("NETFLIX", "nflx", white, 0xFFD81F26.toInt(), 13f, 14, true),
            tbtn("•••", "apps", white, 0xFF444444.toInt(), 15f, 14, true)), full())

        rc.addView(row(54,
            lab(ibtn("src", "src"), "SOURCES"),
            lab(tbtn("TV", "exit", bold = true), "EXIT"),
            lab(ibtn("ls", "menu"), "MENU")), full())

        // yön tuşları
        val pad = Pad(this, { k -> sv.requestDisallowInterceptTouchEvent(true); down(k) }, { release() })
        rc.addView(pad, LinearLayout.LayoutParams(dp(220), dp(220)).apply { setMargins(0, dp(4), 0, dp(4)) })

        rc.addView(row(40, ibtn("bk", "back"), ibtn("as", "assist"), ibtn("hm", "home")), full())
        rc.addView(row(40, ibtn("rw", "rew"), ibtn("pl", "play"), ibtn("ff", "ff")), full())
        rc.addView(row(54,
            lab(tbtn("+", "info", size = 22f), "INFO"),
            lab(ibtn("pa", "pause"), " "),
            lab(ibtn("st", "stop"), " ")), full())
        rc.addView(row(28,
            cbtn(0xFFE8392F.toInt(), "red"), cbtn(0xFF2FC24A.toInt(), "green"),
            cbtn(0xFFE8E4DA.toInt(), "yellow"), cbtn(0xFF27B3E6.toInt(), "blue")), full())

        // ses / kanal
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        fun cell(v: View) = mid.addView(v, LinearLayout.LayoutParams(0, dp(140), 1f).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        })
        cell(rocker("vu", "vd", "vo"))
        val ctr = LinearLayout(this)
        ctr.orientation = LinearLayout.VERTICAL
        ctr.addView(lab(ibtn("ls", "guide"), "TV GUIDE"), LinearLayout.LayoutParams(-1, dp(58)))
        ctr.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        ctr.addView(ibtn("mu", "mute"), LinearLayout.LayoutParams(-1, dp(40)))
        cell(ctr)
        cell(rocker("cu", "cd", "ls"))
        rc.addView(mid, full())

        // rakamlar
        for (r in 0..2) {
            rc.addView(row(40, *Array(3) { c -> val n = r * 3 + c + 1; tbtn("$n", "d$n", size = 17f) }), full())
        }
        rc.addView(row(40,
            tbtn("TEXT", "txt", size = 10f, bold = true),
            tbtn("0", "d0", size = 17f),
            ibtn("ge", "set")), full())

        rc.addView(TextView(this).apply {
            text = "PHILIPS"; textSize = 11f; setTextColor(0xFF9A9AA3.toInt()); letterSpacing = 0.18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        setContentView(sv)
    }

    override fun onPause() { release(); super.onPause() }
}

/** Tuş simgeleri (çizim). */
class Icon(c: Context, private val kind: String, private val col: Int) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var s = 0f

    private fun poly(cv: Canvas, vararg xy: Float) {
        val pa = Path()
        pa.moveTo(xy[0] * s, xy[1] * s)
        var i = 2
        while (i < xy.size) { pa.lineTo(xy[i] * s, xy[i + 1] * s); i += 2 }
        pa.close()
        p.style = Paint.Style.FILL
        cv.drawPath(pa, p)
    }

    private fun line(cv: Canvas, a: Float, b: Float, c: Float, d: Float) {
        p.style = Paint.Style.STROKE
        cv.drawLine(a * s, b * s, c * s, d * s, p)
    }

    private fun arc(cv: Canvas, l: Float, t: Float, r: Float, b: Float, st: Float, sw: Float) {
        p.style = Paint.Style.STROKE
        cv.drawArc(RectF(l * s, t * s, r * s, b * s), st, sw, false, p)
    }

    private fun speaker(cv: Canvas) = poly(cv, -1.1f, -.35f, -.55f, -.35f, 0f, -.9f, 0f, .9f, -.55f, .35f, -1.1f, .35f)

    override fun onDraw(cv: Canvas) {
        s = minOf(width, height) * 0.27f
        cv.translate(width / 2f, height / 2f)
        p.color = col
        p.strokeWidth = s * 0.26f
        p.strokeCap = Paint.Cap.ROUND
        when (kind) {
            "pl" -> poly(cv, -.7f, -.9f, .9f, 0f, -.7f, .9f)
            "pa" -> { poly(cv, -.8f, -.9f, -.25f, -.9f, -.25f, .9f, -.8f, .9f); poly(cv, .25f, -.9f, .8f, -.9f, .8f, .9f, .25f, .9f) }
            "st" -> poly(cv, -.8f, -.8f, .8f, -.8f, .8f, .8f, -.8f, .8f)
            "rw" -> { poly(cv, 0f, -.8f, 0f, .8f, -.95f, 0f); poly(cv, .95f, -.8f, .95f, .8f, 0f, 0f) }
            "ff" -> { poly(cv, 0f, -.8f, 0f, .8f, .95f, 0f); poly(cv, -.95f, -.8f, -.95f, .8f, 0f, 0f) }
            "bk" -> { line(cv, .9f, 0f, -.9f, 0f); line(cv, -.9f, 0f, -.25f, -.65f); line(cv, -.9f, 0f, -.25f, .65f) }
            "hm" -> poly(cv, 0f, -1f, 1f, -.1f, .7f, -.1f, .7f, .9f, .2f, .9f, .2f, .3f, -.2f, .3f, -.2f, .9f, -.7f, .9f, -.7f, -.1f, -1f, -.1f)
            "ls" -> { line(cv, -.9f, -.6f, .9f, -.6f); line(cv, -.9f, 0f, .9f, 0f); line(cv, -.9f, .6f, .9f, .6f) }
            "pw" -> { arc(cv, -.85f, -.85f, .85f, .85f, -60f, 300f); line(cv, 0f, -1.05f, 0f, -.1f) }
            "mu" -> { speaker(cv); line(cv, .35f, -.4f, 1f, .4f); line(cv, 1f, -.4f, .35f, .4f) }
            "vo" -> { speaker(cv); arc(cv, -.4f, -.5f, .6f, .5f, -50f, 100f); arc(cv, -.85f, -.95f, 1.05f, .95f, -50f, 100f) }
            "src" -> {
                p.style = Paint.Style.STROKE
                cv.drawRect(RectF(-1f * s, -.8f * s, .1f * s, .8f * s), p)
                line(cv, -.3f, 0f, 1f, 0f); line(cv, .5f, -.45f, 1f, 0f); line(cv, .5f, .45f, 1f, 0f)
            }
            "ge" -> {
                p.style = Paint.Style.STROKE
                cv.drawCircle(0f, 0f, .5f * s, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(cv, (.7 * cos(a)).toFloat(), (.7 * sin(a)).toFloat(), (1.0 * cos(a)).toFloat(), (1.0 * sin(a)).toFloat())
                }
            }
            "as" -> {
                p.style = Paint.Style.FILL
                p.color = 0xFF4285F4.toInt(); cv.drawCircle(-.35f * s, .1f * s, .75f * s, p)
                p.color = 0xFFEA4335.toInt(); cv.drawCircle(.55f * s, -.6f * s, .3f * s, p)
                p.color = 0xFFFBBC05.toInt(); cv.drawCircle(.8f * s, .1f * s, .3f * s, p)
                p.color = 0xFF34A853.toInt(); cv.drawCircle(.45f * s, .8f * s, .3f * s, p)
            }
        }
    }
}

/** Elmas biçimli yön tuşu: 4 yön + ortada OK. */
class Pad(c: Context, private val onDown: (String) -> Unit, private val onUp: () -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cur: String? = null

    private fun diag() = minOf(width, height) / 2f * 0.98f   // elmasın yarı köşegeni

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val d = diag()
        val half = d / 1.4142f
        cv.save()
        cv.rotate(45f, cx, cy)
        val r = RectF(cx - half, cy - half, cx + half, cy + half)
        p.style = Paint.Style.FILL; p.color = 0xFF18181C.toInt()
        cv.drawRoundRect(r, half * .18f, half * .18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF34343B.toInt()
        cv.drawRoundRect(r, half * .18f, half * .18f, p)
        cv.restore()

        // basılı bölgeyi aydınlat
        val z = cur
        if (z != null && z != "ok") {
            val (ux, uy) = when (z) { "up" -> 0f to -1f; "down" -> 0f to 1f; "left" -> -1f to 0f; else -> 1f to 0f }
            val px = -uy; val py = ux
            val pa = Path()
            pa.moveTo(cx, cy)
            pa.lineTo(cx + (ux + px) * d / 2, cy + (uy + py) * d / 2)
            pa.lineTo(cx + ux * d * .96f, cy + uy * d * .96f)
            pa.lineTo(cx + (ux - px) * d / 2, cy + (uy - py) * d / 2)
            pa.close()
            p.style = Paint.Style.FILL; p.color = 0x33FFFFFF
            cv.drawPath(pa, p)
        }
        // oklar
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND; p.color = 0x66FFFFFF
        for ((ux, uy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            val tx = cx + ux * d * .74f; val ty = cy + uy * d * .74f
            val q = d * .07f
            val px = -uy; val py = ux
            cv.drawLine(tx - ux * q + px * q, ty - uy * q + py * q, tx, ty, p)
            cv.drawLine(tx - ux * q - px * q, ty - uy * q - py * q, tx, ty, p)
        }
        // OK
        p.style = Paint.Style.FILL; p.color = if (z == "ok") 0xFF3A3A42.toInt() else 0xFF0D0D10.toInt()
        cv.drawCircle(cx, cy, d * .36f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = 0xFF3A3A42.toInt()
        cv.drawCircle(cx, cy, d * .36f, p)
    }

    private fun zone(x: Float, y: Float): String? {
        val dx = x - width / 2f
        val dy = y - height / 2f
        val d = diag()
        if (abs(dx) + abs(dy) > d) return null
        if (dx * dx + dy * dy < (d * .36f) * (d * .36f)) return "ok"
        return if (abs(dx) > abs(dy)) (if (dx > 0) "right" else "left") else (if (dy > 0) "down" else "up")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cur = zone(e.x, e.y)
                cur?.let { onDown(it) }
                invalidate()
                return cur != null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (cur != null) onUp()
                cur = null
                invalidate()
            }
        }
        return true
    }
}
