package com.traintimings.vvs

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Redraws the widgets from cached data at the start of every minute so countdowns stay right.
 * Uses a non-wakeup alarm, so it pauses while the screen is off and costs almost no battery.
 */
object MinuteTicker {

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextMinute = now - now % 60_000 + 60_000 + 500
        val pi = pendingIntent(ctx)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExact(AlarmManager.RTC, nextMinute, pi) else am.set(AlarmManager.RTC, nextMinute, pi)
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
