package com.example.minilauncher

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ------------------------------------------------------------------ model

enum class LiveKind { MEDIA, NOTIF, CALL, TIMER, PROGRESS, CHARGE, QUICK }

class LiveAction(
    val label: String,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val run: () -> Unit
)

class QuickData(
    val torchAvailable: Boolean,
    val torchOn: Boolean,
    val volume: Int,
    val volumeMax: Int,
    val batteryPct: Int,
    val onTorch: () -> Unit,
    val onCamera: () -> Unit,
    val onSettings: () -> Unit,
    val onVolume: (Int) -> Unit
)

class Live(
    val key: String,
    val kind: LiveKind,
    val title: String = "",
    val subtitle: String = "",
    val icon: Drawable? = null,
    val controller: MediaController? = null,
    val playing: Boolean = false,
    val progress: Int = -1,
    val big: String = "",
    val chronoBase: Long = 0L,
    val countDown: Boolean = false,
    val durationMs: Long = 0L,
    val demoStart: Long = 0L,
    val actions: List<LiveAction> = emptyList(),
    val quick: QuickData? = null,
    val onTap: (() -> Unit)? = null
) {
    val sig: String
        get() = "$key|$kind|$title|$subtitle|$playing|$progress|$big|${quick?.torchOn}|" +
            "${actions.size}|${System.identityHashCode(icon)}"
}

fun mixColor(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    return Color.rgb(r, g, bl)
}

// ------------------------------------------------------------------ small custom views

/** Little equalizer bars. */
class BarsView(context: Context, private val accentColor: Int) : View(context) {
    var active = true
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val rect = RectF()
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(c: Canvas) {
        val n = 4
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w / (n * 2f - 1f)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        for (i in 0 until n) {
            val amp = if (active) {
                0.3f + 0.7f * (0.5f + 0.5f * sin(t * (4.2f + i * 1.7f) + i * 1.9f))
            } else 0.2f
            val bh = max(bw, h * amp)
            val x = i * bw * 2f
            rect.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f)
            c.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
        if (active) postInvalidateOnAnimation()
    }
}

/** Thin pink gradient progress bar; optionally seekable. */
class GlowBar(
    context: Context,
    private val accentColor: Int,
    private val thickPx: Float
) : View(context) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var dragging = false
    var onSeek: ((Float) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fill.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            mixColor(accentColor, Color.WHITE, 0.45f), accentColor, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val top = (h - thickPx) / 2f
        rect.set(0f, top, w, top + thickPx)
        c.drawRoundRect(rect, thickPx / 2f, thickPx / 2f, track)
        if (value > 0f) {
            val fw = max(thickPx, w * value)
            rect.set(0f, top, fw, top + thickPx)
            c.drawRoundRect(rect, thickPx / 2f, thickPx / 2f, fill)
            if (onSeek != null) c.drawCircle(fw - thickPx / 2f, h / 2f, thickPx * 1.1f, knob)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = onSeek ?: return false
        val w = max(1, width).toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                value = e.x / w
            }
            MotionEvent.ACTION_MOVE -> value = e.x / w
            MotionEvent.ACTION_UP -> {
                value = e.x / w
                dragging = false
                cb(value)
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
            else -> {}
        }
        return true
    }
}

// ------------------------------------------------------------------ the island

class IslandView(context: Context, private val wm: WindowManager) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val scale = IslandPrefs.scale(context)
    private val accent = IslandPrefs.accent(context)
    private val haptics = IslandPrefs.bool(context, IslandPrefs.HAPTICS, true)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val GRAY = 0xFF9A9AA0.toInt()

    private fun dp(v: Int): Int = Math.round(v * density * scale)

    private val gapPx = dp(IslandPrefs.gap(context))

    val lp: WindowManager.LayoutParams = WindowManager.LayoutParams(
        dp(110), dp(34),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = Math.round(IslandPrefs.top(context) * density)
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    var onCollapsed: (() -> Unit)? = null
    var onLongPressed: (() -> Unit)? = null

    private var items: List<Live> = emptyList()
    private var index = 0
    private var expanded = false
    private var lastSig = ""
    private var anim: ValueAnimator? = null
    private var glow = 0f
    private var pulseLevel = 0f
    private var downX = 0f
    private var downY = 0f

    // views that tick
    private var timerText: TextView? = null
    private var trailTimer: TextView? = null
    private var progressBar: GlowBar? = null
    private var elapsedText: TextView? = null
    private var totalText: TextView? = null
    private var bars: BarsView? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    private val collapseRunnable = Runnable { collapse() }

    private val ticker = object : Runnable {
        override fun run() {
            updateDynamic()
            postDelayed(this, 500)
        }
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { handleTap(); return true }
        override fun onLongPress(e: MotionEvent) { handleLongPress() }
    })

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0, 0, view.width, view.height,
                    min(view.height / 2f, dp(28).toFloat())
                )
            }
        }
    }

    // ---------------------------------------------------------- public API

    fun bind(list: List<Live>, focusKey: String?) {
        val oldKey = items.getOrNull(index)?.key
        items = list
        index = when {
            focusKey != null -> list.indexOfFirst { it.key == focusKey }.coerceAtLeast(0)
            oldKey != null -> list.indexOfFirst { it.key == oldKey }.coerceAtLeast(0)
            else -> 0
        }
        rebuild()
    }

    fun setExpanded(exp: Boolean, openForMs: Long = 7000) {
        removeCallbacks(collapseRunnable)
        if (expanded != exp) {
            expanded = exp
            lastSig = ""
        }
        rebuild()
        if (exp) postDelayed(collapseRunnable, openForMs)
    }

    /** Brief brighter pink border pulse; used when something new arrives. */
    fun flash() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1100
            addUpdateListener {
                pulseLevel = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---------------------------------------------------------- interaction

    private fun tick() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun collapse() {
        setExpanded(false)
        onCollapsed?.invoke()
    }

    private fun keepOpen() {
        if (expanded) {
            removeCallbacks(collapseRunnable)
            postDelayed(collapseRunnable, 7000)
        }
    }

    private fun handleTap() {
        val l = items.getOrNull(index) ?: return
        if (!expanded) {
            tick()
            setExpanded(true)
        } else {
            if (l.kind != LiveKind.QUICK) l.onTap?.invoke()
            collapse()
        }
    }

    private fun handleLongPress() {
        tick()
        onLongPressed?.invoke()
    }

    private fun page(delta: Int) {
        if (items.size < 2) return
        index = (index + delta + items.size) % items.size
        lastSig = ""
        tick()
        rebuild()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                val kind = items.getOrNull(index)?.kind
                if (expanded && kind != LiveKind.CALL) collapse()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val th = dp(36)
                if (abs(dy) > th && abs(dy) > abs(dx)) {
                    tick()
                    if (dy > 0) setExpanded(true) else collapse()
                } else if (abs(dx) > th) {
                    page(if (dx < 0) 1 else -1)
                }
            }
            else -> {}
        }
        detector.onTouchEvent(e)
        return true
    }

    // ---------------------------------------------------------- drawing

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val c0 = Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent))
        glowPaint.shader = RadialGradient(
            w * 0.15f, h.toFloat(), max(w, h) * 0.75f,
            intArrayOf(c0, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (glow > 0.01f && glowPaint.shader != null) {
            glowPaint.alpha = (glow * 255).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val sw = max(1f, density * scale * 1.2f)
        strokePaint.strokeWidth = sw
        strokePaint.color = accent
        val a = (0.35f + 0.25f * glow + 0.4f * pulseLevel).coerceIn(0f, 1f)
        strokePaint.alpha = (a * 255).toInt()
        val r = min(height / 2f, dp(28).toFloat()) - sw / 2f
        rectF.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        canvas.drawRoundRect(rectF, r, r, strokePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        removeCallbacks(collapseRunnable)
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------- building

    private fun rebuild() {
        val l = items.getOrNull(index) ?: return
        val sig = items.joinToString(",") { it.sig } + "#$index#$expanded"
        if (sig == lastSig) return
        lastSig = sig

        timerText = null
        trailTimer = null
        progressBar = null
        elapsedText = null
        totalText = null
        bars = null

        removeAllViews()
        val body = if (expanded) buildExpanded(l) else buildCollapsed(l)
        if (expanded) {
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        } else {
            addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        animateToTarget()
        updateDynamic()
    }

    private fun targetSize(): Pair<Int, Int> {
        val body = getChildAt(0) ?: return Pair(lp.width, lp.height)
        val maxW = resources.displayMetrics.widthPixels - dp(20)
        return if (expanded) {
            val w = min(dp(350), maxW)
            body.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            Pair(w, max(dp(60), body.measuredHeight))
        } else {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(34), View.MeasureSpec.EXACTLY)
            )
            Pair(max(dp(70), body.measuredWidth), dp(34))
        }
    }

    private fun animateToTarget() {
        val (tw, th) = targetSize()
        val sw = lp.width
        val sh = lp.height
        val g0 = glow
        val g1 = if (expanded) 1f else 0f
        if (sw == tw && sh == th && g0 == g1) return
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.width = max(dp(40), (sw + (tw - sw) * f).toInt())
                lp.height = max(dp(20), (sh + (th - sh) * f).toInt())
                glow = (g0 + (g1 - g0) * f).coerceIn(0f, 1f)
                if (isAttachedToWindow) {
                    try {
                        wm.updateViewLayout(this@IslandView, lp)
                    } catch (_: Exception) {
                    }
                    invalidateOutline()
                    invalidate()
                }
            }
            start()
        }
    }

    // ---- helpers

    private fun tv(sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false, lines: Int = 1) =
        TextView(context).apply {
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeSp * density * scale)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }

    private fun shape(fill: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density * scale
        }

    private fun iconView(d: Drawable?, sizeDp: Int, radiusFrac: Float = 0.28f, marginEndDp: Int = 0): ImageView {
        val iv = ImageView(context)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        if (d != null) iv.setImageDrawable(d) else iv.setBackgroundColor(0xFF1C1C1E.toInt())
        iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            marginEnd = dp(marginEndDp)
        }
        iv.clipToOutline = true
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * radiusFrac)
            }
        }
        return iv
    }

    private fun dot(sizeDp: Int, color: Int, a: Float = 1f): View {
        val v = View(context)
        v.background = shape(color, sizeDp.toFloat())
        v.alpha = a
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    private fun w1(marginStartDp: Int = 0) =
        LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(marginStartDp) }

    private fun chip(label: String, filled: Boolean, danger: Boolean = false, onClick: () -> Unit): TextView {
        val fillColor = when {
            danger -> 0xFFFF3B30.toInt()
            filled -> accent
            else -> 0xFF1C1C1E.toInt()
        }
        val txtColor = if (filled && !danger) Color.BLACK else Color.WHITE
        val t = tv(13f, txtColor, true)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(11), dp(14), dp(11))
        t.background = shape(fillColor, 22f)
        t.setOnClickListener {
            keepOpen()
            tick()
            onClick()
        }
        return t
    }

    private fun iconBtn(res: Int, sizeDp: Int, filled: Boolean, onClick: () -> Unit): ImageView {
        val iv = ImageView(context)
        iv.setImageResource(res)
        val pad = dp(if (filled) 15 else 10)
        iv.setPadding(pad, pad, pad, pad)
        if (filled) {
            iv.background = shape(accent, 40f)
            iv.setColorFilter(Color.BLACK)
        } else {
            iv.setColorFilter(Color.WHITE)
        }
        iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        }
        iv.setOnClickListener {
            keepOpen()
            tick()
            onClick()
        }
        return iv
    }

    // ---- collapsed

    private fun buildCollapsed(l: Live): View {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(11), 0, dp(11), 0)
        row.addView(leading(l))
        row.addView(Space(context), LinearLayout.LayoutParams(gapPx, 1))
        row.addView(trailing(l))
        return row
    }

    private fun leading(l: Live): View = when {
        l.kind == LiveKind.QUICK -> dot(6, accent, 0.9f)
        l.icon != null -> iconView(l.icon, 20, 0.3f)
        else -> dot(8, accent)
    }

    private fun callIcon(): View {
        val iv = ImageView(context)
        iv.setImageResource(android.R.drawable.ic_menu_call)
        iv.setColorFilter(accent)
        iv.layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
        return iv
    }

    private fun trailing(l: Live): View = when (l.kind) {
        LiveKind.MEDIA -> {
            val b = BarsView(context, accent)
            b.active = l.playing
            b.layoutParams = LinearLayout.LayoutParams(dp(18), dp(14))
            bars = b
            b
        }
        LiveKind.TIMER, LiveKind.CALL -> {
            if (l.chronoBase > 0) {
                val t = tv(11f, accent, true)
                trailTimer = t
                t
            } else callIcon()
        }
        LiveKind.PROGRESS -> tv(11f, accent, true).also { it.text = "${l.progress}%" }
        LiveKind.CHARGE -> tv(11f, accent, true).also { it.text = l.big }
        LiveKind.NOTIF -> dot(6, accent)
        LiveKind.QUICK -> dot(6, Color.WHITE, 0.25f)
    }

    // ---- expanded

    private fun buildExpanded(l: Live): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(16), dp(18), dp(14))
        when (l.kind) {
            LiveKind.MEDIA -> buildMedia(root, l)
            LiveKind.QUICK -> buildQuick(root, l)
            else -> buildGeneric(root, l)
        }
        if (items.size > 1) {
            val row = LinearLayout(context)
            row.gravity = Gravity.CENTER
            items.forEachIndexed { i, _ ->
                val d = View(context)
                d.background = shape(if (i == index) accent else 0x44FFFFFF, 4f)
                row.addView(d, LinearLayout.LayoutParams(dp(if (i == index) 14 else 6), dp(6)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        return root
    }

    private fun buildMedia(root: LinearLayout, l: Live) {
        val top = LinearLayout(context)
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(iconView(l.icon, 56, 0.22f, 14))
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(16f, Color.WHITE, true).also { it.text = l.title })
        col.addView(tv(13f, GRAY).also { it.text = l.subtitle })
        top.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        val b = BarsView(context, accent)
        b.active = l.playing
        bars = b
        top.addView(b, LinearLayout.LayoutParams(dp(26), dp(22)))
        root.addView(top)

        val prog = LinearLayout(context)
        prog.gravity = Gravity.CENTER_VERTICAL
        val el = tv(11f, GRAY).also { it.text = "0:00" }
        val tot = tv(11f, GRAY).also { it.text = "0:00" }
        val bar = GlowBar(context, accent, dp(5).toFloat())
        val c = l.controller
        if (c != null) {
            bar.onSeek = { f ->
                val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (dur > 0) c.transportControls.seekTo((f * dur).toLong())
            }
        }
        elapsedText = el
        totalText = tot
        progressBar = bar
        prog.addView(el)
        prog.addView(bar, LinearLayout.LayoutParams(0, dp(22), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        prog.addView(tot)
        root.addView(prog, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        val ctl = LinearLayout(context)
        ctl.gravity = Gravity.CENTER
        ctl.addView(iconBtn(android.R.drawable.ic_media_previous, 46, false) {
            l.controller?.transportControls?.skipToPrevious()
        })
        ctl.addView(
            iconBtn(
                if (l.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                56, true
            ) {
                val t = l.controller?.transportControls
                if (l.playing) t?.pause() else t?.play()
            }
        )
        ctl.addView(iconBtn(android.R.drawable.ic_media_next, 46, false) {
            l.controller?.transportControls?.skipToNext()
        })
        root.addView(ctl, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    private fun buildGeneric(root: LinearLayout, l: Live) {
        val top = LinearLayout(context)
        top.gravity = Gravity.CENTER_VERTICAL
        if (l.kind == LiveKind.CHARGE) {
            val holder = LinearLayout(context)
            holder.gravity = Gravity.CENTER
            holder.background = shape(0xFF1C1C1E.toInt(), 23f)
            holder.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) }
            val ic = ImageView(context)
            ic.setImageDrawable(l.icon)
            holder.addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)))
            top.addView(holder)
        } else {
            top.addView(iconView(l.icon, 46, 0.3f, 12))
        }
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.addView(tv(15f, Color.WHITE, true).also { it.text = l.title })
        if (l.subtitle.isNotEmpty()) {
            col.addView(tv(13f, GRAY, false, if (l.kind == LiveKind.NOTIF) 2 else 1).also { it.text = l.subtitle })
        }
        top.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        when (l.kind) {
            LiveKind.TIMER, LiveKind.CALL -> if (l.chronoBase > 0) {
                val t = tv(24f, accent, true)
                timerText = t
                top.addView(t)
            }
            LiveKind.CHARGE -> top.addView(tv(28f, accent, true).also { it.text = l.big })
            LiveKind.PROGRESS -> top.addView(tv(22f, accent, true).also { it.text = "${l.progress}%" })
            else -> {}
        }
        root.addView(top)

        if (l.kind == LiveKind.PROGRESS || l.kind == LiveKind.CHARGE) {
            val bar = GlowBar(context, accent, dp(6).toFloat())
            bar.value = max(0, l.progress) / 100f
            root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(16)).apply { topMargin = dp(10) })
        }

        if (l.actions.isNotEmpty()) {
            val row = LinearLayout(context)
            l.actions.forEachIndexed { i, a ->
                row.addView(
                    chip(a.label, a.primary, a.danger) {
                        a.run()
                        collapse()
                    },
                    w1(if (i > 0) 10 else 0)
                )
            }
            root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
        }
    }

    private fun buildQuick(root: LinearLayout, l: Live) {
        val q = l.quick ?: return
        val top = LinearLayout(context)
        top.gravity = Gravity.CENTER_VERTICAL

        val timeCol = LinearLayout(context)
        timeCol.orientation = LinearLayout.VERTICAL
        val clock = TextClock(context)
        clock.format12Hour = "h:mm"
        clock.format24Hour = "HH:mm"
        clock.setTextColor(Color.WHITE)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f * density * scale)
        clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        clock.includeFontPadding = false
        val date = TextClock(context)
        date.format12Hour = "EEE, MMM d"
        date.format24Hour = "EEE, MMM d"
        date.setTextColor(accent)
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * density * scale)
        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        timeCol.addView(clock)
        timeCol.addView(date)
        top.addView(timeCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val batt = LinearLayout(context)
        batt.orientation = LinearLayout.VERTICAL
        batt.gravity = Gravity.END
        batt.addView(tv(20f, accent, true).also { it.text = "${q.batteryPct}%" })
        batt.addView(tv(11f, GRAY).also { it.text = "battery" })
        top.addView(batt)
        root.addView(top)

        val row = LinearLayout(context)
        if (q.torchAvailable) {
            row.addView(chip("Flashlight", q.torchOn) { q.onTorch() }, w1(0))
        }
        row.addView(chip("Camera", false) {
            q.onCamera()
            collapse()
        }, w1(if (q.torchAvailable) 8 else 0))
        row.addView(chip("Settings", false) {
            q.onSettings()
            collapse()
        }, w1(8))
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        val vol = LinearLayout(context)
        vol.gravity = Gravity.CENTER_VERTICAL
        val vi = ImageView(context)
        vi.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        vi.setColorFilter(GRAY)
        vol.addView(vi, LinearLayout.LayoutParams(dp(22), dp(22)))
        val sb = SeekBar(context)
        sb.max = q.volumeMax
        sb.progress = q.volume
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.thumbTintList = ColorStateList.valueOf(accent)
        sb.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    q.onVolume(p)
                    keepOpen()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {
                s?.parent?.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        vol.addView(sb, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
        root.addView(vol, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
    }

    // ---- live updating bits (timers, progress)

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun mediaTimes(l: Live): Pair<Long, Long> {
        val c = l.controller
        if (c == null) {
            val dur = max(1L, l.durationMs)
            return Pair((System.currentTimeMillis() - l.demoStart) % dur, dur)
        }
        val st = c.playbackState
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var pos = max(0L, st?.position ?: 0L)
        if (st != null && st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (dur > 0) pos = pos.coerceIn(0L, dur)
        return Pair(pos, dur)
    }

    private fun updateDynamic() {
        val l = items.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        when (l.kind) {
            LiveKind.MEDIA -> {
                val (pos, dur) = mediaTimes(l)
                val pb = progressBar
                if (pb != null && !pb.dragging) pb.value = if (dur > 0) pos.toFloat() / dur else 0f
                elapsedText?.text = fmt(pos)
                totalText?.text = if (dur > 0) fmt(dur) else "--:--"
            }
            LiveKind.TIMER, LiveKind.CALL -> {
                if (l.chronoBase > 0) {
                    val ms = if (l.countDown) l.chronoBase - now else now - l.chronoBase
                    val s = fmt(max(0L, ms))
                    timerText?.text = s
                    trailTimer?.text = s
                }
            }
            else -> {}
        }
    }
}
