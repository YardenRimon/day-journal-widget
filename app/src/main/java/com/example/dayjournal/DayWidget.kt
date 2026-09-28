package com.example.dayjournal

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Button
import androidx.glance.background
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale

private val routineKey = ActionParameters.Key<String>("routine_id")
private val screenKey = ActionParameters.Key<String>("screen")

class DayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = DayRepository(context)
        val date = activeDay()
        val data = repository.day(date)
        val label = date.format(DateTimeFormatter.ofPattern("EEEE d/M", Locale("he")))
        provideContent { WidgetBody(label, data.routines) }
    }

    @Composable
    private fun WidgetBody(dateLabel: String, routines: List<DayRoutine>) {
        Column(modifier = GlanceModifier.fillMaxSize().background(Color.White).padding(12)) {
            Text("היום · $dateLabel", style = TextStyle(color = ColorProvider(Color.Black), fontSize = 18.sp))
            if (routines.isEmpty()) Text("הוסף רוטינות באפליקציה")
            routines.forEach { routine ->
                CheckBox(
                    checked = routine.done,
                    onCheckedChange = actionRunCallback<ToggleRoutineAction>(
                        actionParametersOf(routineKey to routine.routineId)
                    ),
                    text = routine.title,
                    maxLines = 1,
                )
            }
            Button("סיכום ערב", onClick = actionStartActivity<MainActivity>(
                actionParametersOf(screenKey to "evening")
            ))
        }
    }
}

class ToggleRoutineAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[routineKey] ?: return
        val repository = DayRepository(context)
        val checked = parameters[ToggleableStateKey]
        if (checked == null) repository.toggleRoutine(id)
        else repository.setRoutineDone(activeDay(), id, checked)
        DayWidget().update(context, glanceId)
    }
}

class DayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayWidget()
}
