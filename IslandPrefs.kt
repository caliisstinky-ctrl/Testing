package com.example.minilauncher

import android.content.Context

object IslandPrefs {
    const val FILE = "island"
    const val TOP = "top_dp"
    const val GAP = "gap_dp"
    const val SCALE = "scale_pct"
    const val ACCENT = "accent"
    const val ALWAYS_ON = "always_on"
    const val HAPTICS = "haptics"
    const val F_MEDIA = "f_media"
    const val F_NOTIF = "f_notif"
    const val F_CALLS = "f_calls"
    const val F_CHARGE = "f_charge"
    const val F_TIMERS = "f_timers"

    /** Hot pink first, then other pink-family accents. */
    val ACCENTS = intArrayOf(
        0xFFFF2E93.toInt(), // hot pink
        0xFFFF10F0.toInt(), // neon magenta
        0xFFFF8AC4.toInt(), // bubblegum
        0xFFFF4D6D.toInt(), // rose
        0xFFD946EF.toInt()  // orchid
    )

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun int(c: Context, k: String, d: Int): Int = p(c).getInt(k, d)
    fun bool(c: Context, k: String, d: Boolean): Boolean = p(c).getBoolean(k, d)
    fun putInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()
    fun putBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()

    fun accent(c: Context): Int = int(c, ACCENT, ACCENTS[0])
    fun top(c: Context): Int = int(c, TOP, 8)
    fun gap(c: Context): Int = int(c, GAP, 26)
    fun scale(c: Context): Float = int(c, SCALE, 100) / 100f
}
