package com.example.dayjournal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ACTION_TOGGLE = "com.example.dayjournal.TOGGLE_ROUTINE"
private const val EXTRA_ROUTINE_ID = "routine_id"
private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val widgetMutex = Mutex()
private val widgetDate = DateTimeFormatter.ofPattern("EEEE d/M", Locale.forLanguageTag("he"))

class DayWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        widgetScope.launch {
            try {
                widgetMutex.withLock { appWidgetIds.forEach { render(context, manager, it) } }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) {
            super.onReceive(context, intent)
            return
        }
        val routineId = intent.getStringExtra(EXTRA_ROUTINE_ID) ?: return
        val pending = goAsync()
        widgetScope.launch {
            try {
                widgetMutex.withLock {
                    DayRepository(context).toggleRoutine(routineId)
                    renderAll(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) {
            widgetMutex.withLock { renderAll(context) }
        }

        private suspend fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val widgetIds = manager.getAppWidgetIds(ComponentName(context, DayWidgetReceiver::class.java))
            widgetIds.forEach { render(context, manager, it) }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val date = activeDay()
            val routines = DayRepository(context).day(date).routines
            val views = RemoteViews(context.packageName, R.layout.widget_root)
            views.setTextViewText(R.id.widget_date, "היום · ${date.format(widgetDate)}")
            views.removeAllViews(R.id.widget_routines)
            if (routines.isEmpty()) {
                val empty = RemoteViews(context.packageName, R.layout.widget_routine_large)
                empty.setTextViewText(R.id.widget_routine_title, "הוסף רוטינות באפליקציה")
                views.addView(R.id.widget_routines, empty)
            } else {
                routines.forEach { routine ->
                    val rowLayout = if (routines.size <= 3) R.layout.widget_routine_large else R.layout.widget_routine_compact
                    val row = RemoteViews(context.packageName, rowLayout)
                    row.setTextViewText(R.id.widget_routine_title, "${if (routine.done) "☑" else "☐"}  ${routine.title}")
                    val toggle = Intent(context, DayWidgetReceiver::class.java).apply {
                        action = ACTION_TOGGLE
                        data = Uri.parse("dayjournal://widget/$widgetId/${routine.routineId}")
                        putExtra(EXTRA_ROUTINE_ID, routine.routineId)
                    }
                    val action = PendingIntent.getBroadcast(
                        context, 0, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    row.setOnClickPendingIntent(R.id.widget_routine_title, action)
                    views.addView(R.id.widget_routines, row)
                }
            }
            val evening = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.parse("dayjournal://widget/evening/$widgetId")
                putExtra("screen", "evening")
            }
            views.setOnClickPendingIntent(
                R.id.widget_evening,
                PendingIntent.getActivity(context, 0, evening, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            manager.updateAppWidget(widgetId, views)
        }
    }
}
