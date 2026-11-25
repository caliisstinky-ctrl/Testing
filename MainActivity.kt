package com.example.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

data class AppEntry(
    val label: String,
    val pkg: String,
    val icon: Drawable,
    val launch: Intent,
    val initials: String
)

class MainActivity : Activity() {

    private var allApps: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private val adapter = AppAdapter()
    private var accent = 0

    private val GRAY = 0xFF9A9AA0.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accent = IslandPrefs.accent(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(56), dp(20), dp(8))
        }

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            setTextColor(accent)
            textSize = 15f
            letterSpacing = 0.05f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(clock)
        root.addView(date)

        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0xFF6C6C70.toInt())
            setTextColor(Color.WHITE)
            setSingleLine()
            textSize = 16f
            background = null
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(18), dp(14), dp(8), dp(14))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { open(it) }
                true
            }
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(18), 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, IslandSettingsActivity::class.java))
            }
        }
        val pillBg = GradientDrawable().apply {
            setColor(0xFF0E0E10.toInt())
            cornerRadius = dp(30).toFloat()
            setStroke(dp(1), Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
        }
        row.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(gear, LinearLayout.LayoutParams(WRAP, MATCH))
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(28)
        })

        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            gravity = Gravity.CENTER
            adapter = this@MainActivity.adapter
            clipToPadding = false
            selector = null
            setPadding(0, dp(24), 0, 0)
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> showOptions(shown[pos]); true }
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        search.setText("")
        hideKeyboard()
        grid.smoothScrollToPosition(0)
    }

    @Deprecated("Launchers shouldn't close on back")
    override fun onBackPressed() {}

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    val pkg = it.activityInfo.packageName
                    val label = it.loadLabel(pm).toString()
                    AppEntry(
                        label = label,
                        pkg = pkg,
                        icon = it.loadIcon(pm),
                        launch = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setClassName(pkg, it.activityInfo.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        },
                        initials = label.split(" ", "-", "_")
                            .mapNotNull { w -> w.firstOrNull() }.joinToString("").lowercase()
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { allApps = list; applyFilter() }
        }.start()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown = if (q.isEmpty()) allApps else allApps.filter {
            it.label.lowercase().contains(q) || it.initials.startsWith(q)
        }
        adapter.notifyDataSetChanged()
    }

    private fun open(app: AppEntry) {
        hideKeyboard()
        startActivity(app.launch)
    }

    private fun showOptions(app: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf("App info", "Uninstall")) { _, which ->
                val uri = Uri.parse("package:${app.pkg}")
                if (which == 0) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                } else {
                    startActivity(Intent(Intent.ACTION_DELETE, uri))
                }
            }.show()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
    }

    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val app = shown[p]
            val cell = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(54), dp(54)))
                addView(TextView(context).apply {
                    setTextColor(0xFFD8D8DC.toInt())
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                        else -> {}
                    }
                    false
                }
            }
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }
}
