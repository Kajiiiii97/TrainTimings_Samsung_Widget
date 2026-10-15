package com.traintimings.vvs

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Wakes the widgets every 30 seconds while the screen is on: redraws the countdowns and, when the
 * data is due, fetches fresh real-time times (see [Refresher]).
 * Uses a non-wakeup alarm, so it pauses while the screen is off and costs almost no battery.
 */
object LiveTicker {
    private const val INTERVAL_MS = 30_000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextTick = now - now % INTERVAL_MS + INTERVAL_MS + 500
        val pi = pendingIntent(ctx)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExact(AlarmManager.RTC, nextTick, pi) else am.set(AlarmManager.RTC, nextTick, pi)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx))
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, TrainWidgetProvider::class.java).setAction(TrainWidgetProvider.ACTION_TICK)
        return PendingIntent.getBroadcast(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
