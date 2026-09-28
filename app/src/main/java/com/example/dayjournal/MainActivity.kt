package com.example.dayjournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Screen { TODAY, EVENING, JOURNAL, DAY, ROUTINES }
private val hebrewDate = DateTimeFormatter.ofPattern("EEEE, d בMMMM yyyy", Locale("he"))
private val wakeTimePattern = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startEvening = intent.getStringExtra("screen") == "evening"
        setContent {
            CompositionRtl {
                MaterialTheme { JournalApp(startEvening) }
            }
        }
    }
}

@Composable
private fun CompositionRtl(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        content()
    }
}

@Composable
private fun JournalApp(startEvening: Boolean) {
    val context = LocalContext.current
    val repository = remember(context) { DayRepository(context) }
    val scope = rememberCoroutineScope()
    var currentDate by remember { mutableStateOf(activeDay()) }
    var firstDate by remember { mutableStateOf(currentDate) }
    var screen by remember { mutableStateOf(if (startEvening) Screen.EVENING else Screen.TODAY) }
    var selectedDate by remember { mutableStateOf(currentDate) }
    var current by remember { mutableStateOf<DaySnapshot?>(null) }
    var tomorrow by remember { mutableStateOf<DaySnapshot?>(null) }
    var selected by remember { mutableStateOf<DaySnapshot?>(null) }
    var templates by remember { mutableStateOf<List<RoutineTemplate>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            val date = activeDay()
            if (date != currentDate) {
                currentDate = date
                DayWidget().updateAll(context)
            }
            revision++
            delay(60_000)
        }
    }
    LaunchedEffect(currentDate, selectedDate, revision) {
        current = repository.day(currentDate)
        tomorrow = repository.day(currentDate.plusDays(1))
        selected = repository.day(selectedDate)
        templates = repository.allTemplates()
        firstDate = repository.firstDay()
    }
    fun commit(block: suspend () -> Unit, after: () -> Unit = {}) {
        scope.launch {
            block()
            revision++
            DayWidget().updateAll(context)
            after()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Text("יומן יום", style = MaterialTheme.typography.headlineMedium)
            Text(currentDate.format(hebrewDate), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavButton("היום", screen == Screen.TODAY) { screen = Screen.TODAY }
                NavButton("ערב", screen == Screen.EVENING) { screen = Screen.EVENING }
                NavButton("יומן", screen == Screen.JOURNAL || screen == Screen.DAY) { screen = Screen.JOURNAL }
                NavButton("רוטינות", screen == Screen.ROUTINES) { screen = Screen.ROUTINES }
            }
            Spacer(Modifier.height(16.dp))
            when (screen) {
                Screen.TODAY -> ScrollPage {
                    Text("רוטינות הבוקר", style = MaterialTheme.typography.titleLarge)
                    RoutineChecks(current?.routines.orEmpty(), currentDate) { date, id, done ->
                        commit { repository.setRoutineDone(date, id, done) }
                    }
                    if (current?.routines?.isEmpty() == true) {
                        OutlinedButton(onClick = { screen = Screen.ROUTINES }) { Text("הוסף רוטינות") }
                    }
                    HorizontalDivider()
                    Text("המשימות שלי היום", style = MaterialTheme.typography.titleLarge)
                    TaskChecks(current?.tasks.orEmpty()) { task, done ->
                        commit { repository.setTaskDone(task, done) }
                    }
                    current?.entry?.wakeTime?.takeIf { it.isNotEmpty() }?.let {
                        Text("שעת השכמה שתכננת: $it")
                    }
                    Button(onClick = { screen = Screen.EVENING }) { Text("סיכום יום ותכנון מחר") }
                }
                Screen.EVENING -> ScrollPage {
                    EveningPage(currentDate, current, tomorrow,
                        onRoutine = { id, done -> commit { repository.setRoutineDone(currentDate, id, done) } },
                        onSave = { summary, tasks, wake ->
                            commit({
                                repository.saveSummary(currentDate, summary)
                                repository.saveTasks(currentDate.plusDays(1), tasks)
                                repository.saveWakeTime(currentDate.plusDays(1), wake)
                            }, { screen = Screen.TODAY })
                        }
                    )
                }
                Screen.JOURNAL -> {
                    val days = generateSequence(currentDate) { date ->
                        if (date > firstDate) date.minusDays(1) else null
                    }.toList()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(days) { date ->
                            Column(modifier = Modifier.fillMaxWidth().clickable {
                                selectedDate = date
                                screen = Screen.DAY
                            }.padding(vertical = 12.dp)) {
                                Text(date.format(hebrewDate), style = MaterialTheme.typography.titleMedium)
                                Text("פתח את היום", style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Screen.DAY -> ScrollPage {
                    Text(selectedDate.format(hebrewDate), style = MaterialTheme.typography.titleLarge)
                    DayPage(selectedDate, selected,
                        onRoutine = { id, done -> commit { repository.setRoutineDone(selectedDate, id, done) } },
                        onTask = { task, done -> commit { repository.setTaskDone(task, done) } },
                        onSave = { summary, tasks, wake ->
                            commit({
                                repository.saveSummary(selectedDate, summary)
                                repository.saveTasks(selectedDate, tasks)
                                repository.saveWakeTime(selectedDate, wake)
                            }, { screen = Screen.JOURNAL })
                        }
                    )
                }
                Screen.ROUTINES -> ScrollPage {
                    RoutinesPage(templates,
                        onAdd = { title -> commit { repository.addTemplate(title) } },
                        onRename = { id, title -> commit { repository.renameTemplate(id, title) } },
                        onRemove = { id -> commit { repository.removeTemplate(id) } },
                        onRestore = { id -> commit { repository.restoreTemplate(id) } },
                        onMove = { id, direction -> commit { repository.moveTemplate(id, direction) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, content = { Text(label) })
    else OutlinedButton(onClick = onClick, content = { Text(label) })
}

@Composable
private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun RoutineChecks(items: List<DayRoutine>, day: LocalDate, onChange: (LocalDate, String, Boolean) -> Unit) {
    if (items.isEmpty()) Text("אין סימונים ליום הזה")
    items.forEach { item ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = item.done, onCheckedChange = { onChange(day, item.routineId, it) })
            Text(item.title, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun TaskChecks(items: List<DayTask>, onChange: (DayTask, Boolean) -> Unit) {
    if (items.isEmpty()) Text("אין משימות שנקבעו ליום הזה")
    items.forEach { item ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = item.done, onCheckedChange = { onChange(item, it) })
            Text(item.title, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun EveningPage(
    day: LocalDate,
    today: DaySnapshot?,
    tomorrow: DaySnapshot?,
    onRoutine: (String, Boolean) -> Unit,
    onSave: (String, List<String>, String) -> Unit,
) {
    var summary by remember(day, today?.entry?.summary) { mutableStateOf(today?.entry?.summary.orEmpty()) }
    var first by remember(day, tomorrow?.tasks) { mutableStateOf(tomorrow?.tasks?.getOrNull(0)?.title.orEmpty()) }
    var second by remember(day, tomorrow?.tasks) { mutableStateOf(tomorrow?.tasks?.getOrNull(1)?.title.orEmpty()) }
    var third by remember(day, tomorrow?.tasks) { mutableStateOf(tomorrow?.tasks?.getOrNull(2)?.title.orEmpty()) }
    var wake by remember(day, tomorrow?.entry?.wakeTime) { mutableStateOf(tomorrow?.entry?.wakeTime.orEmpty()) }
    var error by remember { mutableStateOf(false) }

    Text("ריטואל ערב", style = MaterialTheme.typography.titleLarge)
    Text("סמן מה שביצעת היום ועדיין לא סימנת")
    RoutineChecks(today?.routines.orEmpty(), day) { _, id, done -> onRoutine(id, done) }
    OutlinedTextField(value = summary, onValueChange = { summary = it },
        label = { Text("סיכום היום") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
    HorizontalDivider()
    Text("עד 3 משימות למחר · ${day.plusDays(1).format(hebrewDate)}", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(first, { first = it }, label = { Text("משימה 1") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(second, { second = it }, label = { Text("משימה 2") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(third, { third = it }, label = { Text("משימה 3") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(wake, { wake = it }, label = { Text("שעת השכמה למחר · 07:00") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error)
    if (error) Text("הזן שעה בפורמט 24 שעות, למשל 07:00", color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        error = wake.isNotBlank() && !wakeTimePattern.matches(wake)
        if (!error) onSave(summary, listOf(first, second, third), wake)
    }) { Text("שמור את הערב") }
}

@Composable
private fun DayPage(
    date: LocalDate,
    data: DaySnapshot?,
    onRoutine: (String, Boolean) -> Unit,
    onTask: (DayTask, Boolean) -> Unit,
    onSave: (String, List<String>, String) -> Unit,
) {
    if (data?.entry == null) Text("אין עדיין רישום ליום הזה")
    Text("רוטינות", style = MaterialTheme.typography.titleMedium)
    RoutineChecks(data?.routines.orEmpty(), date) { _, id, done -> onRoutine(id, done) }
    Text("משימות", style = MaterialTheme.typography.titleMedium)
    TaskChecks(data?.tasks.orEmpty(), onTask)
    var summary by remember(date, data?.entry?.summary) { mutableStateOf(data?.entry?.summary.orEmpty()) }
    var first by remember(date, data?.tasks) { mutableStateOf(data?.tasks?.getOrNull(0)?.title.orEmpty()) }
    var second by remember(date, data?.tasks) { mutableStateOf(data?.tasks?.getOrNull(1)?.title.orEmpty()) }
    var third by remember(date, data?.tasks) { mutableStateOf(data?.tasks?.getOrNull(2)?.title.orEmpty()) }
    var wake by remember(date, data?.entry?.wakeTime) { mutableStateOf(data?.entry?.wakeTime.orEmpty()) }
    var error by remember { mutableStateOf(false) }
    OutlinedTextField(summary, { summary = it }, label = { Text("סיכום היום") },
        modifier = Modifier.fillMaxWidth(), minLines = 3)
    OutlinedTextField(first, { first = it }, label = { Text("משימה 1") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(second, { second = it }, label = { Text("משימה 2") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(third, { third = it }, label = { Text("משימה 3") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(wake, { wake = it }, label = { Text("שעת השכמה שתוכננה") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error)
    if (error) Text("הזן שעה בפורמט 24 שעות, למשל 07:00", color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        error = wake.isNotBlank() && !wakeTimePattern.matches(wake)
        if (!error) onSave(summary, listOf(first, second, third), wake)
    }) { Text("שמור שינויים") }
}

@Composable
private fun RoutinesPage(
    templates: List<RoutineTemplate>,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onRestore: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    Text("עריכת רוטינות", style = MaterialTheme.typography.titleLarge)
    Text("שינוי שם או הסרה יחולו על היום והלאה. ימים קודמים יישמרו כפי שהיו.")
    templates.forEach { template ->
        var title by remember(template.id, template.title) { mutableStateOf(template.title) }
        OutlinedTextField(title, { title = it }, label = { Text("שם רוטינה") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (template.active) {
                OutlinedButton(onClick = { onRename(template.id, title) }) { Text("שמור שם") }
                OutlinedButton(onClick = { onRemove(template.id) }) { Text("הסר") }
                OutlinedButton(onClick = { onMove(template.id, -1) }) { Text("↑") }
                OutlinedButton(onClick = { onMove(template.id, 1) }) { Text("↓") }
            } else {
                OutlinedButton(onClick = { onRestore(template.id) }) { Text("החזר לרוטינות") }
            }
        }
        HorizontalDivider()
    }
    var newTitle by remember { mutableStateOf("") }
    OutlinedTextField(newTitle, { newTitle = it }, label = { Text("רוטינה חדשה") },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    Button(onClick = {
        if (newTitle.isNotBlank()) {
            onAdd(newTitle)
            newTitle = ""
        }
    }) { Text("הוסף רוטינה") }
}
