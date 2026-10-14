package com.traintimings.vvs

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
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

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var mode = Mode.OVERVIEW
    private var requestToken = 0

    /** The stops chosen so far, in the order they were added. */
    private val picked = mutableListOf<StopSelection>()
    /** Departures downloaded while picking lines, reused to fill the widget straight away. */
    private val fetched = mutableMapOf<String, DepartureCache>()

    private var searchResults: List<Stop> = emptyList()
    private var currentStop: Stop? = null
    private var options: List<LineDirection> = emptyList()

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
                Mode.OVERVIEW -> picked.getOrNull(position)?.let { pickStop(Stop(it.stopId, it.stopName)) }
                Mode.STOPS -> searchResults.getOrNull(position)?.let { pickStop(it) }
                Mode.LINES -> Unit
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (mode == Mode.OVERVIEW) picked.getOrNull(position)?.let { confirmRemove(it) }
            mode == Mode.OVERVIEW
        }
        save.setOnClickListener { if (mode == Mode.LINES) commitLines() else save() }
        back.setOnClickListener { goBack() }
        addStop.setOnClickListener { showSearch() }

        if (hasWidget()) WidgetStore.config(this, widgetId)?.let { picked += it.stops }
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
        val rows = picked.map { sel ->
            val lines = if (sel.lines.isEmpty()) getString(R.string.all_lines) else sel.lines.joinToString("\n") { it.label }
            "${sel.stopName}\n$lines"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
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
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, searchResults.map { it.name })
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
        val seen = LinkedHashMap<LineDirection, Int>()
        departures.forEach { seen.putIfAbsent(it.lineDirection, it.productClass) }
        // Keep previously saved choices even if they don't run right now (e.g. late at night).
        preselected.forEach { seen.putIfAbsent(it, -1) }

        options = seen.entries
            .sortedWith(LineOrder.comparator({ it.key }, { it.value }))
            .map { it.key }

        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, options.map { it.label })
        options.forEachIndexed { i, opt -> list.setItemChecked(i, opt in preselected) }
        status.setText(if (options.isEmpty()) R.string.no_lines else R.string.pick_lines)
        save.visibility = View.VISIBLE
    }

    /** "Done" on the lines screen: add the stop, or update it if it was already picked. */
    private fun commitLines() {
        val stop = currentStop ?: return
        val checked = list.checkedItemPositions
        val lines = options.filterIndexed { i, _ -> checked?.get(i) == true }
        val selection = StopSelection(stop.id, stop.name, lines)
        val index = picked.indexOfFirst { it.stopId == stop.id }
        if (index >= 0) picked[index] = selection else picked += selection
        query.text.clear()
        searchResults = emptyList()
        showOverview()
    }

    private fun confirmRemove(selection: StopSelection) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_stop, selection.stopName))
            .setPositiveButton(R.string.remove) { _, _ ->
                picked.remove(selection)
                showOverview()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (picked.isEmpty()) return
        val config = WidgetConfig(picked.toList())
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
