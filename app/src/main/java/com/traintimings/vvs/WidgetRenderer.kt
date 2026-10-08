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
        val lines = Countdown.group(cache?.departures.orEmpty().filter(config::matches), now)
            .take(rowCount(mgr, id))

        if (lines.isEmpty()) {
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
            lines.forEach { views.addView(R.id.rows, row(ctx, it, now, fmt)) }
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

    private fun row(ctx: Context, line: UpcomingLine, now: Instant, fmt: DateTimeFormatter): RemoteViews {
        val d = line.next
        val r = RemoteViews(ctx.packageName, R.layout.widget_row)
        r.setTextViewText(R.id.badge_text, d.line)
        r.setInt(R.id.badge_bg, "setColorFilter", LineColors.background(d.line, d.productClass))
        r.setTextViewText(R.id.destination, d.destination)

        if (d.platform.isNotBlank()) {
            r.setTextViewText(R.id.platform, ctx.getString(R.string.platform_short, d.platform))
            r.setViewVisibility(R.id.platform, View.VISIBLE)
        } else {
            r.setViewVisibility(R.id.platform, View.GONE)
        }

        // Big countdown for the next train.
        val minutes = Countdown.minutesUntil(d.time, now)
        when {
            minutes == 0L -> {
                r.setTextViewText(R.id.countdown, ctx.getString(R.string.now))
                r.setViewVisibility(R.id.countdown_unit, View.GONE)
            }
            minutes >= 60 -> {
                r.setTextViewText(R.id.countdown, fmt.format(d.time))
                r.setViewVisibility(R.id.countdown_unit, View.GONE)
            }
            else -> {
                r.setTextViewText(R.id.countdown, minutes.toString())
                r.setViewVisibility(R.id.countdown_unit, View.VISIBLE)
            }
        }
        if (d.delayMinutes > 0) {
            r.setTextViewText(R.id.delay, "+${d.delayMinutes}")
            r.setViewVisibility(R.id.delay, View.VISIBLE)
        } else {
            r.setViewVisibility(R.id.delay, View.GONE)
        }

        // Smaller "then 14 min" for the train after.
        val after = line.after
        if (after != null) {
            r.setTextViewText(R.id.then, ctx.getString(R.string.then_in, shortWait(ctx, after, now, fmt)))
            r.setViewVisibility(R.id.then, View.VISIBLE)
        } else {
            r.setViewVisibility(R.id.then, View.GONE)
        }
        return r
    }

    private fun shortWait(ctx: Context, d: Departure, now: Instant, fmt: DateTimeFormatter): String {
        val minutes = Countdown.minutesUntil(d.time, now)
        return if (minutes >= 60) fmt.format(d.time) else ctx.getString(R.string.minutes_short, minutes)
    }

    private fun showMessage(views: RemoteViews, text: String) {
        views.setTextViewText(R.id.empty_text, text)
        views.setViewVisibility(R.id.empty_text, View.VISIBLE)
    }

    /** Fit as many rows as the widget's current height allows. */
    private fun rowCount(mgr: AppWidgetManager, id: Int): Int {
        val height = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (height <= 0) return 4
        return ((height - 70) / 44).coerceIn(1, 6)
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
