package com.traintimings.vvs

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle

/**
 * Invisible screen opened by tapping the widget. Android (and Samsung's power saving in particular)
 * can block the internet for apps in the background, which a widget always is; an app on screen is
 * never blocked. So a tap briefly brings this see-through screen up, downloads, and closes it.
 */
class RefreshActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        TrainWidgetProvider.allIds(app).forEach {
            WidgetRenderer.render(app, mgr, it, getString(R.string.widget_refreshing))
        }
        Thread {
            try {
                Refresher.refreshAll(app, keepLoadingOnFailure = false)
            } finally {
                runOnUiThread {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
        }.start()
    }
}
