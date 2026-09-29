package com.originghostplayer.android

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Lets the user pick apps that should never be mirrored (see [IgnoredApps]). Apps that recently
 * played media come first (see [MediaHistory]), then every other launchable app.
 */
class IgnoredAppsActivity : Activity() {

    private class AppEntry(val pkg: String, val label: String, val icon: Drawable?, val lastPlayed: Long?)

    private sealed class Row {
        class Section(val title: String) : Row()
        class App(val app: AppEntry, val background: Int) : Row()
    }

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private val adapter = Adapter()

    private var recent: List<AppEntry> = emptyList()
    private var others: List<AppEntry> = emptyList()
    private var loaded = false
    private var query = ""
    private val ignored = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ignored_apps)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.back_button).setOnClickListener { finish() }
        listView = findViewById(R.id.app_list)
        emptyText = findViewById(R.id.empty_text)
        listView.adapter = adapter
        findViewById<EditText>(R.id.search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                rebuild()
            }
        })

        ignored.addAll(IgnoredApps.get(this))
        rebuild()
        loadApps()
    }

    /** Resolving labels and icons for every installed app is slow enough to jank the UI thread. */
    private fun loadApps() {
        val appContext = applicationContext
        Thread {
            val pm = appContext.packageManager
            val self = appContext.packageName
            val history = MediaHistory.all(appContext)
            // Snapshot: already-ignored apps go first in each section. Taken once, when the page
            // opens, so rows don't jump around under the user's finger while toggling.
            val ignoredAtOpen = IgnoredApps.get(appContext)
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            ).map { it.activityInfo.packageName }

            fun entry(pkg: String): AppEntry? = runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                AppEntry(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), history[pkg])
            }.getOrNull()

            // Uninstalled apps drop out here — getApplicationInfo throws for them.
            val recentList = history.keys
                .filter { it != self }
                .sortedWith(compareBy<String> { it !in ignoredAtOpen }.thenByDescending { history[it] })
                .mapNotNull(::entry)
            val recentPkgs = recentList.map { it.pkg }.toSet()
            // Ignored apps with no launcher icon still get a row, so they can be un-ignored.
            val otherList = (launchable + ignoredAtOpen)
                .distinct()
                .filter { it != self && it !in recentPkgs }
                .mapNotNull(::entry)
                .sortedWith(compareBy<AppEntry> { it.pkg !in ignoredAtOpen }.thenBy { it.label.lowercase() })

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                recent = recentList
                others = otherList
                loaded = true
                rebuild()
                // The list fills in after the page is already on screen — start it at the top.
                listView.setSelection(0)
            }
        }.start()
    }

    private fun rebuild() {
        val rows = mutableListOf<Row>()
        fun addSection(title: Int, apps: List<AppEntry>) {
            val matches = apps.filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) return
            rows += Row.Section(getString(title))
            matches.forEachIndexed { i, app ->
                val bg = when {
                    matches.size == 1 -> R.drawable.bg_card_single
                    i == 0 -> R.drawable.bg_card_top
                    i == matches.lastIndex -> R.drawable.bg_card_bottom
                    else -> R.drawable.bg_card_middle
                }
                rows += Row.App(app, bg)
            }
        }
        addSection(R.string.section_recent, recent)
        addSection(R.string.section_other, others)
        adapter.rows = rows

        emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = getString(if (loaded) R.string.no_apps_found else R.string.loading_apps)
    }

    private fun subtitleFor(app: AppEntry): String? {
        val time = app.lastPlayed ?: return null
        if (MediaProbeListener.instance?.currentController()?.packageName == app.pkg) {
            return getString(R.string.playing_now)
        }
        return DateUtils.getRelativeTimeSpanString(
            time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }

    private inner class Adapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(parent.context)
            return when (val row = rows[position]) {
                is Row.Section -> {
                    val view = convertView ?: inflater.inflate(R.layout.item_app_section, parent, false)
                    (view as TextView).text = row.title
                    view
                }
                is Row.App -> {
                    val view = convertView ?: inflater.inflate(R.layout.item_app, parent, false)
                    bindApp(view, row)
                    view
                }
            }
        }

        private fun bindApp(view: View, row: Row.App) {
            val app = row.app
            val container = view.findViewById<View>(R.id.app_row)
            val switch = view.findViewById<Switch>(R.id.app_switch)
            val sub = view.findViewById<TextView>(R.id.app_sub)
            container.setBackgroundResource(row.background)
            view.findViewById<ImageView>(R.id.app_icon).setImageDrawable(app.icon)
            view.findViewById<TextView>(R.id.app_name).text = app.label
            val subtitle = subtitleFor(app)
            sub.text = subtitle
            sub.visibility = if (subtitle == null) View.GONE else View.VISIBLE

            // Recycled rows: detach the old listener before restoring this row's state.
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = app.pkg in ignored
            switch.contentDescription = getString(R.string.ignore_app, app.label)
            switch.setOnCheckedChangeListener { _, checked ->
                if (checked) ignored.add(app.pkg) else ignored.remove(app.pkg)
                IgnoredApps.setIgnored(this@IgnoredAppsActivity, app.pkg, checked)
            }
            container.setOnClickListener { switch.toggle() }
        }
    }
}
