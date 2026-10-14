package com.traintimings.vvs

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Builds a widget from one or more stops:
 * the overview lists the chosen stops, "Add stop" searches for one, and ticking its lines adds it.
 * Opened by the launcher when a widget is added, from the widget's gear icon, or from the app drawer.
 */
class ConfigActivity : Activity() {

    private enum class Mode { OVERVIEW, STOPS, LINES }

    private companion object {
        val HIGHLIGHT_CHOICES = listOf(WidgetConfig.HIGHLIGHT_OFF, 0, 1, 2, 3, 4, 5, 7, 10, 15)
        val CARD_STYLES = intArrayOf(R.drawable.card_bg_1, R.drawable.card_bg_2, R.drawable.card_bg_3, R.drawable.card_bg_4)
        val BADGE_STYLES = intArrayOf(R.drawable.badge_1, R.drawable.badge_2, R.drawable.badge_3, R.drawable.badge_4)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var mode = Mode.OVERVIEW
    private var requestToken = 0

    /** The stops chosen so far. */
    private val picked = mutableListOf<StopSelection>()
    /** The widget's tile order: one row per chosen line, or per stop showing all its lines. */
    private val order = mutableListOf<TileKey>()
    /** Per-row highlight threshold in minutes; rows without an entry use the default. */
    private val highlight = mutableMapOf<TileKey, Int>()
    /** Departures downloaded while picking lines, reused to fill the widget straight away. */
    private val fetched = mutableMapOf<String, DepartureCache>()

    private var searchResults: List<Stop> = emptyList()
    private var currentStop: Stop? = null
    private var options: List<LineDirection> = emptyList()
    private var lineOptions: List<LineOptions.Option> = emptyList()

    private lateinit var searchRow: View
    private lateinit var query: EditText
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var save: Button
    private lateinit var back: Button
    private lateinit var addStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // If the user backs out of first-time setup the launcher removes the widget.
        setResult(RESULT_CANCELED, resultIntent())
        setContentView(R.layout.activity_config)

        searchRow = findViewById(R.id.search_row)
        query = findViewById(R.id.query)
        title = findViewById(R.id.title)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        save = findViewById(R.id.save)
        back = findViewById(R.id.back)
        addStop = findViewById(R.id.add_stop)

        findViewById<Button>(R.id.search).setOnClickListener { search() }
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { search(); true } else false
        }
        list.setOnItemClickListener { _, _, position, _ ->
            when (mode) {
                Mode.OVERVIEW -> order.getOrNull(position)?.let { key ->
                    picked.find { it.stopId == key.stopId }?.let { pickStop(Stop(it.stopId, it.stopName)) }
                }
                Mode.STOPS -> searchResults.getOrNull(position)?.let { pickStop(it) }
                Mode.LINES -> Unit
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (mode == Mode.OVERVIEW) order.getOrNull(position)?.let { confirmRemove(it) }
            mode == Mode.OVERVIEW
        }
        save.setOnClickListener { if (mode == Mode.LINES) commitLines() else save() }
        back.setOnClickListener { goBack() }
        addStop.setOnClickListener { showSearch() }

        if (hasWidget()) WidgetStore.config(this, widgetId)?.let {
            picked += it.stops
            order += it.order
            highlight += it.highlight
        }
        syncOrder()
        if (picked.isEmpty()) showSearch() else showOverview()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == Mode.OVERVIEW || (mode == Mode.STOPS && picked.isEmpty())) super.onBackPressed() else goBack()
    }

    private fun goBack() {
        when (mode) {
            Mode.LINES -> showSearch()
            Mode.STOPS -> if (picked.isEmpty()) finish() else showOverview()
            Mode.OVERVIEW -> finish()
        }
    }

    private fun hasWidget() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun showOverview() {
        mode = Mode.OVERVIEW
        requestToken++
        searchRow.visibility = View.GONE
        back.visibility = View.GONE
        addStop.visibility = View.VISIBLE
        save.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        save.setText(if (hasWidget()) R.string.save else R.string.add_to_home)
        title.setText(R.string.your_stops)
        status.setText(R.string.your_stops_hint)
        list.choiceMode = ListView.CHOICE_MODE_NONE
        list.adapter = OrderAdapter()
    }

    private fun stopName(stopId: String) = picked.find { it.stopId == stopId }?.stopName.orEmpty()

    private fun move(from: Int, to: Int) {
        if (to !in order.indices) return
        order.add(to, order.removeAt(from))
        (list.adapter as? OrderAdapter)?.notifyDataSetChanged()
    }

    /** Keeps [order] in step with [picked]: drops rows that no longer exist, appends new ones at the end. */
    private fun syncOrder() {
        val wanted = picked.flatMap { sel ->
            if (sel.lines.isEmpty()) listOf(TileKey(sel.stopId, null)) else sel.lines.map { TileKey(sel.stopId, it) }
        }
        order.retainAll(wanted.toSet())
        wanted.filterNot { it in order }.forEach { order += it }
    }

    private inner class OrderAdapter : BaseAdapter() {
        override fun getCount() = order.size
        override fun getItem(position: Int) = order[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_tile_entry, parent, false)
            val key = order[position]
            // Same playful cycling as the widget tiles: each row gets the next pop colour.
            val pop = position % CARD_STYLES.size
            v.findViewById<View>(R.id.card).setBackgroundResource(CARD_STYLES[pop])
            v.findViewById<TextView>(R.id.position).apply {
                text = (position + 1).toString()
                setBackgroundResource(BADGE_STYLES[pop])
            }
            v.findViewById<TextView>(R.id.line).text =
                key.line?.label ?: getString(R.string.all_lines)
            v.findViewById<TextView>(R.id.stop).text = stopName(key.stopId)
            v.findViewById<Button>(R.id.highlight).apply {
                text = highlightChip(highlight[key] ?: WidgetConfig.DEFAULT_HIGHLIGHT)
                setBackgroundResource(BADGE_STYLES[pop])
                setOnClickListener { chooseHighlight(key) }
            }
            v.findViewById<Button>(R.id.up).apply {
                isEnabled = position > 0
                setOnClickListener { move(position, position - 1) }
            }
            v.findViewById<Button>(R.id.down).apply {
                isEnabled = position < order.size - 1
                setOnClickListener { move(position, position + 1) }
            }
            return v
        }
    }

    private fun highlightChip(minutes: Int): String = when (minutes) {
        WidgetConfig.HIGHLIGHT_OFF -> getString(R.string.highlight_chip_off)
        0 -> getString(R.string.highlight_chip_now)
        else -> getString(R.string.highlight_chip, minutes)
    }

    /** Scroll-wheel picker for when this row's tile lights up. */
    private fun chooseHighlight(key: TileKey) {
        val values = HIGHLIGHT_CHOICES
        val labels = values.map {
            when (it) {
                WidgetConfig.HIGHLIGHT_OFF -> getString(R.string.highlight_off)
                0 -> getString(R.string.highlight_now)
                else -> getString(R.string.highlight_within, it)
            }
        }.toTypedArray()

        val dialog = Dialog(this, R.style.PlayfulDialog)
        dialog.setContentView(R.layout.dialog_highlight)
        dialog.findViewById<TextView>(R.id.highlight_line).text =
            (key.line?.label ?: getString(R.string.all_lines)) + " · " + stopName(key.stopId)
        val picker = dialog.findViewById<NumberPicker>(R.id.picker).apply {
            minValue = 0
            maxValue = labels.size - 1
            displayedValues = labels
            wrapSelectorWheel = false
            value = values.indexOf(highlight[key] ?: WidgetConfig.DEFAULT_HIGHLIGHT).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                textColor = getColor(R.color.screen_text)
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, resources.displayMetrics)
                selectionDividerHeight = (resources.displayMetrics.density * 2).toInt()
            }
        }
        dialog.findViewById<Button>(R.id.cancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.ok).setOnClickListener {
            highlight[key] = values[picker.value]
            (list.adapter as? OrderAdapter)?.notifyDataSetChanged()
            dialog.dismiss()
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun showSearch() {
        mode = Mode.STOPS
        requestToken++
        searchRow.visibility = View.VISIBLE
        back.visibility = if (picked.isEmpty()) View.GONE else View.VISIBLE
        addStop.visibility = View.GONE
        save.visibility = View.GONE
        title.setText(R.string.pick_stop)
        status.text = if (searchResults.isEmpty()) getString(R.string.search_hint_long) else ""
        list.choiceMode = ListView.CHOICE_MODE_NONE
        list.adapter = ArrayAdapter(this, R.layout.item_card, searchResults.map { it.name })
    }

    private fun search() {
        val text = query.text.toString().trim()
        if (text.length < 2) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        status.setText(R.string.searching)
        val token = ++requestToken
        io.execute {
            val result = runCatching {
                val found = VvsApi.searchStops(text)
                // Raw EFA stop IDs (e.g. 5002260, as used by the LCD display sketch) work directly too.
                if (text.all { it.isDigit() } && found.none { it.id.endsWith(text) }) {
                    listOf(Stop(text, getString(R.string.stop_id_entry, text))) + found
                } else found
            }
            main.post {
                if (isFinishing || token != requestToken) return@post
                result.onSuccess {
                    searchResults = it
                    showSearch()
                    if (it.isEmpty()) status.setText(R.string.no_stops)
                }.onFailure {
                    status.text = getString(R.string.network_error, it.message ?: "")
                }
            }
        }
    }

    private fun pickStop(stop: Stop) {
        currentStop = stop
        mode = Mode.LINES
        searchRow.visibility = View.GONE
        back.visibility = View.VISIBLE
        addStop.visibility = View.GONE
        save.visibility = View.GONE
        save.setText(R.string.done)
        title.text = stop.name
        status.setText(R.string.loading_lines)
        list.adapter = null
        val token = ++requestToken
        io.execute {
            val result = runCatching { VvsApi.board(stop.id, 120) }
            main.post {
                if (isFinishing || token != requestToken) return@post
                result.onSuccess { board ->
                    if (stop.name == getString(R.string.stop_id_entry, stop.id) && board.stopName != null) {
                        currentStop = Stop(stop.id, board.stopName)
                        title.text = board.stopName
                    }
                    fetched[stop.id] = DepartureCache(Instant.now(), board.departures, null)
                    showLines(board.departures, picked.find { it.stopId == stop.id }?.lines.orEmpty().toSet())
                }.onFailure { status.text = getString(R.string.network_error, it.message ?: "") }
            }
        }
    }

    private fun showLines(departures: List<Departure>, preselected: Set<LineDirection>) {
        lineOptions = LineOptions.build(departures, preselected)
        options = lineOptions.map { it.line }

        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(this, R.layout.item_check, options.map { it.label })
        lineOptions.forEachIndexed { i, opt -> list.setItemChecked(i, opt.checked) }
        status.setText(if (options.isEmpty()) R.string.no_lines else R.string.pick_lines)
        save.visibility = View.VISIBLE
    }

    /** "Done" on the lines screen: add the stop, or update it if it was already picked. */
    private fun commitLines() {
        val stop = currentStop ?: return
        val checked = list.checkedItemPositions
        val chosen = lineOptions.filterIndexed { i, _ -> checked?.get(i) == true }
        val selection = StopSelection(stop.id, stop.name, chosen.map { it.line })
        val index = picked.indexOfFirst { it.stopId == stop.id }
        if (index >= 0) picked[index] = selection else picked += selection

        // Older picks may have been merged into one direction row: move their tile position and
        // highlight time onto the new row instead of losing them.
        for (option in chosen) {
            val newKey = TileKey(stop.id, option.line)
            for (old in option.replaces) {
                val oldKey = TileKey(stop.id, old)
                if (oldKey == newKey) continue
                val at = order.indexOf(oldKey)
                if (at >= 0) order[at] = newKey
                highlight.remove(oldKey)?.let { highlight.putIfAbsent(newKey, it) }
            }
        }
        val deduped = order.distinct()
        order.clear()
        order += deduped

        syncOrder()
        query.text.clear()
        searchResults = emptyList()
        showOverview()
    }

    /** Removes one row: a single line, or the whole stop when it was the stop's last line. */
    private fun confirmRemove(key: TileKey) {
        val label = key.line?.let { "${it.label} (${stopName(key.stopId)})" } ?: stopName(key.stopId)
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_stop, label))
            .setPositiveButton(R.string.remove) { _, _ ->
                val index = picked.indexOfFirst { it.stopId == key.stopId }
                if (index >= 0) {
                    val remaining = picked[index].lines - listOfNotNull(key.line).toSet()
                    if (key.line == null || remaining.isEmpty()) picked.removeAt(index)
                    else picked[index] = picked[index].copy(lines = remaining)
                }
                syncOrder()
                showOverview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (picked.isEmpty()) return
        val config = WidgetConfig(picked.toList(), order.toList(), highlight.filterKeys { it in order })
        fetched.forEach { (stopId, cache) -> WidgetStore.saveStopCache(this, stopId, cache) }

        if (hasWidget()) {
            WidgetStore.saveConfig(this, widgetId, config)
            WidgetRenderer.render(this, AppWidgetManager.getInstance(this), widgetId)
            Scheduler.ensurePeriodic(this)
            Scheduler.refreshNow(this)
            MinuteTicker.schedule(this)
            setResult(RESULT_OK, resultIntent())
            finish()
        } else {
            pinWidget(config)
        }
    }

    /** Opened from the app drawer: ask the launcher (One UI supports this) to place a new widget. */
    private fun pinWidget(config: WidgetConfig) {
        val mgr = AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val callback = Intent(this, TrainWidgetProvider::class.java)
            .setAction(TrainWidgetProvider.ACTION_PINNED)
            .putExtra(TrainWidgetProvider.EXTRA_CONFIG, config.toJson().toString())
        // Must be mutable so the launcher can attach the new widget id.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(this, 0, callback, flags)
        mgr.requestPinAppWidget(ComponentName(this, TrainWidgetProvider::class.java), null, pending)
    }
}
