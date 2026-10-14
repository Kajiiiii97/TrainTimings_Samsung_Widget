package com.traintimings.vvs

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object WidgetRenderer {

    private const val MAX_TILES = 6

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int, status: String? = null) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_train)
        views.removeAllViews(R.id.rows)
        views.setOnClickPendingIntent(R.id.btn_settings, configIntent(ctx, id))

        val config = WidgetStore.config(ctx, id)
        if (config == null) {
            views.setTextViewText(R.id.stop_name, ctx.getString(R.string.app_name))
            views.setViewVisibility(R.id.btn_refresh, View.GONE)
            showMessage(views, ctx.getString(R.string.widget_setup))
            views.setTextViewText(R.id.footer, "")
            views.setOnClickPendingIntent(R.id.widget_root, configIntent(ctx, id))
            mgr.updateAppWidget(id, views)
            return
        }

        val refresh = refreshIntent(ctx, id)
        views.setOnClickPendingIntent(R.id.widget_root, refresh)
        views.setOnClickPendingIntent(R.id.btn_refresh, refresh)
        views.setViewVisibility(R.id.btn_refresh, View.VISIBLE)
        views.setTextViewText(R.id.stop_name, config.stopName)

        val fmt = formatter(ctx)
        val cache = WidgetStore.cache(ctx, id)
        val now = Instant.now()
        val (columns, maxRows) = gridSize(mgr, id)
        val tiles = Countdown.tiles(
            cache?.departures.orEmpty().filter(config::matches),
            now,
            minOf(MAX_TILES, columns * maxRows),
        )

        if (tiles.isEmpty()) {
            showMessage(
                views,
                when {
                    cache == null -> ctx.getString(R.string.widget_loading)
                    cache.error != null && cache.fetchedAt == null -> ctx.getString(R.string.widget_error)
                    else -> ctx.getString(R.string.widget_no_departures)
                },
            )
        } else {
            views.setViewVisibility(R.id.empty_text, View.GONE)
            // Fill the grid row by row; pad the last row so tiles keep the same width.
            tiles.chunked(columns).forEach { chunk ->
                val row = RemoteViews(ctx.packageName, R.layout.widget_grid_row)
                chunk.forEach { row.addView(R.id.grid_row, tile(ctx, it, now, fmt)) }
                repeat(columns - chunk.size) {
                    val filler = RemoteViews(ctx.packageName, R.layout.widget_tile)
                    filler.setViewVisibility(R.id.tile_root, View.INVISIBLE)
                    row.addView(R.id.grid_row, filler)
                }
                views.addView(R.id.rows, row)
            }
        }

        val fetchedAt = cache?.fetchedAt
        val footer = status ?: when {
            fetchedAt == null -> ""
            cache?.error != null -> ctx.getString(R.string.widget_offline, fmt.format(fetchedAt))
            else -> ctx.getString(R.string.widget_updated, fmt.format(fetchedAt))
        }
        views.setTextViewText(R.id.footer, footer)
        mgr.updateAppWidget(id, views)
    }

    private fun tile(ctx: Context, line: UpcomingLine, now: Instant, fmt: DateTimeFormatter): RemoteViews {
        val d = line.next
        val t = RemoteViews(ctx.packageName, R.layout.widget_tile)
        t.setTextViewText(R.id.badge_text, d.line)
        t.setTextViewText(R.id.destination, d.destination)

        // Big countdown for the next train; the tile turns orange when it's about to leave.
        val minutes = Countdown.minutesUntil(d.time, now)
        val soon = minutes <= 1
        when {
            minutes == 0L -> {
                t.setTextViewText(R.id.countdown, ctx.getString(R.string.now))
                t.setViewVisibility(R.id.countdown_unit, View.GONE)
            }
            minutes >= 60 -> {
                t.setTextViewText(R.id.countdown, fmt.format(d.time))
                t.setViewVisibility(R.id.countdown_unit, View.GONE)
            }
            else -> {
                t.setTextViewText(R.id.countdown, minutes.toString())
                t.setViewVisibility(R.id.countdown_unit, View.VISIBLE)
            }
        }
        if (d.delayMinutes > 0) {
            t.setTextViewText(R.id.delay, "+${d.delayMinutes}")
            t.setViewVisibility(R.id.delay, View.VISIBLE)
        } else {
            t.setViewVisibility(R.id.delay, View.GONE)
        }

        // Small "then 14 min" for the train after.
        val after = line.after
        if (after != null) {
            t.setTextViewText(R.id.then, ctx.getString(R.string.then_in, shortWait(ctx, after, now, fmt)))
            t.setViewVisibility(R.id.then, View.VISIBLE)
        } else {
            t.setViewVisibility(R.id.then, View.GONE)
        }

        if (soon) {
            val ink = ctx.getColor(R.color.ink_black)
            t.setInt(R.id.tile_root, "setBackgroundResource", R.drawable.tile_soon_bg)
            t.setInt(R.id.badge_text, "setBackgroundResource", R.drawable.badge_soon)
            t.setTextColor(R.id.badge_text, ctx.getColor(R.color.jasmine))
            t.setTextColor(R.id.destination, ink)
            t.setTextColor(R.id.countdown, ink)
            t.setTextColor(R.id.countdown_unit, ink)
            t.setTextColor(R.id.then, ink)
        }
        return t
    }

    private fun shortWait(ctx: Context, d: Departure, now: Instant, fmt: DateTimeFormatter): String {
        val minutes = Countdown.minutesUntil(d.time, now)
        return if (minutes >= 60) fmt.format(d.time) else ctx.getString(R.string.minutes_short, minutes)
    }

    private fun showMessage(views: RemoteViews, text: String) {
        views.setTextViewText(R.id.empty_text, text)
        views.setViewVisibility(R.id.empty_text, View.VISIBLE)
    }

    /** Two columns on phones, three on wide widgets; as many rows as the height fits (max 3). */
    private fun gridSize(mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val options = mgr.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val columns = if (width >= 400) 3 else 2
        val rows = if (height <= 0) 3 else ((height - 56) / 58).coerceIn(1, 3)
        return columns to rows
    }

    private fun formatter(ctx: Context): DateTimeFormatter =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm")
            .withZone(ZoneId.systemDefault())

    private fun refreshIntent(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, TrainWidgetProvider::class.java)
            .setAction(TrainWidgetProvider.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun configIntent(ctx: Context, id: Int): PendingIntent {
        val intent = Intent(ctx, ConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(Uri.parse("traintimings://widget/$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
