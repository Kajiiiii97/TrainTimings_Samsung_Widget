package com.traintimings.vvs

import android.app.Activity
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
import java.util.concurrent.Executors

/**
 * Step 1: search for a VVS stop. Step 2: tick the lines and directions to show.
 * Opened by the launcher when a widget is added, from the widget's gear icon, or from the app drawer.
 */
class ConfigActivity : Activity() {

    private enum class Mode { STOPS, LINES }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var mode = Mode.STOPS
    private var stops: List<Stop> = emptyList()
    private var selectedStop: Stop? = null
    private var options: List<LineDirection> = emptyList()
    private var preselected: Set<LineDirection> = emptySet()
    private var requestToken = 0

    private lateinit var searchRow: View
    private lateinit var query: EditText
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var save: Button
    private lateinit var back: Button

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

        findViewById<Button>(R.id.search).setOnClickListener { search() }
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) { search(); true } else false
        }
        list.setOnItemClickListener { _, _, position, _ ->
            if (mode == Mode.STOPS) stops.getOrNull(position)?.let { pickStop(it) }
        }
        save.setOnClickListener { save() }
        back.setOnClickListener { showStops() }
        save.setText(if (hasWidget()) R.string.save else R.string.add_to_home)

        val existing = if (hasWidget()) WidgetStore.config(this, widgetId) else null
        if (existing != null) {
            preselected = existing.selections.toSet()
            pickStop(Stop(existing.stopId, existing.stopName))
        } else {
            showStops()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == Mode.LINES) showStops() else super.onBackPressed()
    }

    private fun hasWidget() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun showStops() {
        mode = Mode.STOPS
        requestToken++
        searchRow.visibility = View.VISIBLE
        back.visibility = View.GONE
        save.visibility = View.GONE
        title.setText(R.string.pick_stop)
        status.text = if (stops.isEmpty()) getString(R.string.search_hint_long) else ""
        list.choiceMode = ListView.CHOICE_MODE_NONE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, stops.map { it.name })
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
                    stops = it
                    showStops()
                    if (it.isEmpty()) status.setText(R.string.no_stops)
                }.onFailure {
                    status.text = getString(R.string.network_error, it.message ?: "")
                }
            }
        }
    }

    private fun pickStop(stop: Stop) {
        selectedStop = stop
        mode = Mode.LINES
        searchRow.visibility = View.GONE
        back.visibility = View.VISIBLE
        save.visibility = View.GONE
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
                        selectedStop = Stop(stop.id, board.stopName)
                        title.text = board.stopName
                    }
                    showLines(board.departures)
                }
                    .onFailure { status.text = getString(R.string.network_error, it.message ?: "") }
            }
        }
    }

    private fun showLines(departures: List<Departure>) {
        val seen = LinkedHashMap<LineDirection, Int>()
        departures.forEach { seen.putIfAbsent(it.lineDirection, it.productClass) }
        // Keep previously saved choices even if they don't run right now (e.g. late at night).
        preselected.forEach { seen.putIfAbsent(it, -1) }

        options = seen.entries
            .sortedWith(
                compareBy<Map.Entry<LineDirection, Int>>(
                    { rank(it.key.line, it.value) },
                    { lineNumber(it.key.line) },
                    { it.key.line },
                    { it.key.destination },
                ),
            )
            .map { it.key }

        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, options.map { it.label })
        options.forEachIndexed { i, opt -> list.setItemChecked(i, opt in preselected) }
        status.setText(if (options.isEmpty()) R.string.no_lines else R.string.pick_lines)
        save.visibility = View.VISIBLE
    }

    private fun save() {
        val stop = selectedStop ?: return
        val checked = list.checkedItemPositions
        val selections = options.filterIndexed { i, _ -> checked?.get(i) == true }
        val config = WidgetConfig(stop.id, stop.name, selections)

        if (hasWidget()) {
            WidgetStore.saveConfig(this, widgetId, config)
            WidgetRenderer.render(this, AppWidgetManager.getInstance(this), widgetId)
            Scheduler.ensurePeriodic(this)
            Scheduler.refreshNow(this)
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

    private fun rank(line: String, productClass: Int): Int {
        val l = line.uppercase()
        return when {
            l.startsWith("S") && l.getOrNull(1)?.isDigit() == true -> 0
            l.startsWith("U") && l.getOrNull(1)?.isDigit() == true -> 1
            l.startsWith("R") || l.startsWith("MEX") || l.startsWith("IRE") || l.startsWith("IC") -> 2
            productClass in 0..4 -> 3
            else -> 4
        }
    }

    private fun lineNumber(line: String): Int = line.filter { it.isDigit() }.take(4).toIntOrNull() ?: Int.MAX_VALUE
}
