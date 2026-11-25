package com.example.minilauncher

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class IslandSettingsActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val CARD = 0xFF0E0E10.toInt()
    private val EDGE = 0xFF222226.toInt()
    private val GRAY = 0xFF8E8E93.toInt()
    private val accent: Int get() = IslandPrefs.accent(this)

    private lateinit var overlayStatus: TextView
    private lateinit var listenerStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, r: Int, stroke: Int = 0, sw: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(r).toFloat()
        if (sw > 0) setStroke(dp(sw), stroke)
    }

    private fun tv(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    private fun section(title: String) = tv(title.uppercase(), 12f, accent, true).apply {
        letterSpacing = 0.12f
        setPadding(dp(4), dp(18), 0, dp(8))
    }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = shape(CARD, 22, EDGE, 1)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        children.forEach { addView(it) }
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit) =
        tv(label, 14f, if (filled) Color.BLACK else accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = shape(if (filled) accent else Color.TRANSPARENT, 24, accent, 1)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun switchRow(label: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(tv(label, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = IslandPrefs.bool(this, key, def)
        val checked = intArrayOf(android.R.attr.state_checked)
        val states = arrayOf(checked, intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(accent, GRAY))
        sw.trackTintList = ColorStateList(states, intArrayOf(withAlpha(accent, 0x66), 0xFF2C2C2E.toInt()))
        sw.setOnCheckedChangeListener { _, c ->
            IslandPrefs.putBool(this, key, c)
            IslandService.instance?.reload()
        }
        row.addView(sw)
        return row
    }

    private fun sliderRow(label: String, key: String, lo: Int, hi: Int, def: Int, suffix: String): View {
        val value = IslandPrefs.int(this, key, def)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        val valueLabel = tv("$value$suffix", 14f, accent, true)
        head.addView(tv(label, 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(valueLabel)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = value - lo
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.thumbTintList = ColorStateList.valueOf(accent)
        sb.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + lo
                valueLabel.text = "$v$suffix"
                if (fromUser) IslandPrefs.putInt(this@IslandSettingsActivity, key, v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                IslandService.instance?.reload()
                IslandService.instance?.preview("notif")
            }
        })
        box.addView(head)
        box.addView(sb)
        return box
    }

    private fun previewRow(vararg items: Pair<String, String>): View {
        val row = LinearLayout(this)
        items.forEachIndexed { i, (label, kind) ->
            val b = pill(label, false) { preview(kind) }
            b.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                topMargin = dp(8)
                if (i > 0) marginStart = dp(8)
            }
            row.addView(b)
        }
        return row
    }

    private fun preview(kind: String) {
        val svc = IslandService.instance
        if (svc == null || !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay + notification access first", Toast.LENGTH_SHORT).show()
        } else {
            svc.preview(kind)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(box)
        }
        setContentView(scroll)

        val title = SpannableString("Mini Island")
        title.setSpan(ForegroundColorSpan(accent), 5, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        box.addView(tv("", 32f, Color.WHITE, true).apply { text = title })
        box.addView(tv("Live activities for your camera cut-out.", 14f, GRAY))

        // setup
        box.addView(section("Setup"))
        overlayStatus = tv("", 15f)
        listenerStatus = tv("", 15f)
        box.addView(card(
            overlayStatus,
            listenerStatus,
            pill("Allow display over other apps", true) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            pill("Allow notification access", true) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            tv("Greyed out? Sideloaded apps need App info, then the three-dot menu, then " +
                "“Allow restricted settings” first.", 12f, GRAY).apply { setPadding(0, dp(10), 0, 0) },
            pill("Open App info", false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        ))

        // style
        box.addView(section("Style"))
        val chips = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(6)) }
        IslandPrefs.ACCENTS.forEach { col ->
            val ring = if (col == accent) Color.WHITE else Color.TRANSPARENT
            val chip = View(this).apply {
                background = shape(col, 18, ring, 2)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
                setOnClickListener {
                    IslandPrefs.putInt(this@IslandSettingsActivity, IslandPrefs.ACCENT, col)
                    IslandService.instance?.reload()
                    recreate()
                }
            }
            chips.addView(chip)
        }
        box.addView(card(
            tv("Accent colour", 15f),
            chips,
            sliderRow("Vertical position", IslandPrefs.TOP, 0, 80, 8, " dp"),
            sliderRow("Camera gap", IslandPrefs.GAP, 0, 80, 26, " dp"),
            sliderRow("Size", IslandPrefs.SCALE, 70, 140, 100, " %"),
            switchRow("Always show pill", IslandPrefs.ALWAYS_ON, true),
            switchRow("Haptics", IslandPrefs.HAPTICS, true)
        ))

        // features
        box.addView(section("Live activities"))
        box.addView(card(
            switchRow("Music and media", IslandPrefs.F_MEDIA, true),
            switchRow("Notifications", IslandPrefs.F_NOTIF, true),
            switchRow("Calls", IslandPrefs.F_CALLS, true),
            switchRow("Charging and battery", IslandPrefs.F_CHARGE, true),
            switchRow("Timers and downloads", IslandPrefs.F_TIMERS, true)
        ))

        // preview
        box.addView(section("Preview"))
        box.addView(card(
            previewRow("Notification" to "notif", "Call" to "call"),
            previewRow("Music" to "music", "Charging" to "charge"),
            previewRow("Quick panel" to "quick")
        ))

        box.addView(tv(
            "Tap to expand. Swipe down or up to open and close. Swipe sideways to switch " +
                "between activities. Long-press for the quick panel.",
            12f, GRAY
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val listener = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        overlayStatus.text = (if (overlay) "●  " else "○  ") + "Display over other apps"
        overlayStatus.setTextColor(if (overlay) accent else GRAY)
        listenerStatus.text = (if (listener) "●  " else "○  ") + "Notification access"
        listenerStatus.setTextColor(if (listener) accent else GRAY)
    }
}
