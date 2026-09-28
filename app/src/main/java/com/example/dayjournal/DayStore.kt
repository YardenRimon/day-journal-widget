package com.example.dayjournal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** The day labelled Monday runs from Monday 02:00 until Tuesday 01:59 local time. */
fun activeDay(): LocalDate = LocalDateTime.now().minusHours(2).toLocalDate()

@Entity
data class RoutineTemplate(
    @PrimaryKey val id: String,
    val title: String,
    val position: Int,
    val active: Boolean = true,
)

@Entity(primaryKeys = ["day", "routineId"])
data class DayRoutine(
    val day: String,
    val routineId: String,
    val title: String,
    val position: Int,
    val done: Boolean = false,
)

@Entity
data class DayEntry(
    @PrimaryKey val day: String,
    val summary: String = "",
    val wakeTime: String = "",
)

@Entity
data class DayTask(
    @PrimaryKey val id: String,
    val day: String,
    val title: String,
    val position: Int,
    val done: Boolean = false,
)

@Dao
interface DayDao {
    @Query("SELECT * FROM RoutineTemplate ORDER BY position")
    suspend fun allTemplates(): List<RoutineTemplate>

    @Query("SELECT * FROM RoutineTemplate WHERE active = 1 ORDER BY position")
    suspend fun templates(): List<RoutineTemplate>

    @Query("SELECT * FROM RoutineTemplate WHERE id = :id")
    suspend fun template(id: String): RoutineTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTemplate(value: RoutineTemplate)

    @Query("SELECT * FROM DayRoutine WHERE day = :day ORDER BY position")
    suspend fun routines(day: String): List<DayRoutine>

    @Query("SELECT * FROM DayRoutine WHERE day = :day AND routineId = :id")
    suspend fun routine(day: String, id: String): DayRoutine?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addRoutine(value: DayRoutine)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoutine(value: DayRoutine)

    @Query("DELETE FROM DayRoutine WHERE day = :day AND routineId = :id")
    suspend fun deleteRoutine(day: String, id: String)

    @Query("SELECT * FROM DayEntry WHERE day = :day")
    suspend fun entry(day: String): DayEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEntry(value: DayEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntry(value: DayEntry)

    @Query("SELECT * FROM DayTask WHERE day = :day ORDER BY position")
    suspend fun tasks(day: String): List<DayTask>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTask(value: DayTask)

    @Query("DELETE FROM DayTask WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT MIN(day) FROM DayEntry")
    suspend fun firstDay(): String?
}

@Database(
    entities = [RoutineTemplate::class, DayRoutine::class, DayEntry::class, DayTask::class],
    version = 1,
    exportSchema = false,
)
abstract class DayDatabase : RoomDatabase() {
    abstract fun dao(): DayDao

    companion object {
        @Volatile private var instance: DayDatabase? = null
        fun get(context: Context): DayDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DayDatabase::class.java, "day-journal.db"
            ).build().also { instance = it }
        }
    }
}

data class DaySnapshot(
    val entry: DayEntry?,
    val routines: List<DayRoutine>,
    val tasks: List<DayTask>,
)

class DayRepository(context: Context) {
    private val db = DayDatabase.get(context)
    private val dao = db.dao()

    private suspend fun ensureDay(date: LocalDate) = db.withTransaction {
        if (date > activeDay()) return@withTransaction
        val day = date.toString()
        dao.addEntry(DayEntry(day))
        if (dao.routines(day).isEmpty()) {
            dao.templates().forEach { template ->
                dao.addRoutine(DayRoutine(day, template.id, template.title, template.position))
            }
        }
    }

    suspend fun ensureCurrentDay() = ensureDay(activeDay())

    suspend fun day(date: LocalDate): DaySnapshot {
        ensureDay(date)
        val key = date.toString()
        return DaySnapshot(dao.entry(key), dao.routines(key), dao.tasks(key))
    }

    suspend fun firstDay(): LocalDate = dao.firstDay()?.let(LocalDate::parse) ?: activeDay()
    suspend fun templates(): List<RoutineTemplate> {
        ensureCurrentDay()
        return dao.templates()
    }

    suspend fun allTemplates(): List<RoutineTemplate> {
        ensureCurrentDay()
        return dao.allTemplates()
    }

    suspend fun setRoutineDone(date: LocalDate, id: String, done: Boolean) {
        ensureDay(date)
        val key = date.toString()
        dao.routine(key, id)?.let { dao.putRoutine(it.copy(done = done)) }
    }

    suspend fun toggleRoutine(id: String) {
        ensureCurrentDay()
        val key = activeDay().toString()
        dao.routine(key, id)?.let { dao.putRoutine(it.copy(done = !it.done)) }
    }

    suspend fun setTaskDone(task: DayTask, done: Boolean) = dao.putTask(task.copy(done = done))

    suspend fun saveSummary(date: LocalDate, summary: String) {
        val key = date.toString()
        val previous = dao.entry(key) ?: DayEntry(key)
        dao.putEntry(previous.copy(summary = summary))
    }

    suspend fun saveWakeTime(date: LocalDate, wakeTime: String) {
        val key = date.toString()
        val previous = dao.entry(key) ?: DayEntry(key)
        dao.putEntry(previous.copy(wakeTime = wakeTime))
    }

    suspend fun saveTasks(date: LocalDate, titles: List<String>) = db.withTransaction {
        val key = date.toString()
        dao.addEntry(DayEntry(key))
        val old = dao.tasks(key)
        val cleaned = titles.take(3).map { it.trim() }.filter { it.isNotEmpty() }
        val used = mutableSetOf<String>()
        cleaned.forEachIndexed { index, title ->
            val previous = old.firstOrNull { it.title == title && it.id !in used }
                ?: old.getOrNull(index)?.takeIf { it.id !in used }
            previous?.let { used += it.id }
            dao.putTask(DayTask(previous?.id ?: UUID.randomUUID().toString(), key, title, index, previous?.done ?: false))
        }
        old.filter { it.id !in used }.forEach { dao.deleteTask(it.id) }
    }

    suspend fun addTemplate(title: String) = db.withTransaction {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return@withTransaction
        val position = (dao.templates().maxOfOrNull { it.position } ?: -1) + 1
        val template = RoutineTemplate(UUID.randomUUID().toString(), cleaned, position)
        dao.putTemplate(template)
        dao.addRoutine(DayRoutine(activeDay().toString(), template.id, cleaned, position))
    }

    suspend fun renameTemplate(id: String, title: String) = db.withTransaction {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return@withTransaction
        dao.template(id)?.let { dao.putTemplate(it.copy(title = cleaned)) }
        val key = activeDay().toString()
        dao.routine(key, id)?.let { dao.putRoutine(it.copy(title = cleaned)) }
    }

    suspend fun removeTemplate(id: String) = db.withTransaction {
        dao.template(id)?.let { dao.putTemplate(it.copy(active = false)) }
        dao.deleteRoutine(activeDay().toString(), id)
    }

    suspend fun restoreTemplate(id: String) = db.withTransaction {
        val template = dao.template(id) ?: return@withTransaction
        dao.putTemplate(template.copy(active = true))
        val key = activeDay().toString()
        dao.addRoutine(DayRoutine(key, id, template.title, template.position))
    }

    suspend fun moveTemplate(id: String, direction: Int) = db.withTransaction {
        val active = dao.templates()
        val index = active.indexOfFirst { it.id == id }
        val otherIndex = index + direction
        if (index < 0 || otherIndex !in active.indices) return@withTransaction
        val first = active[index]
        val second = active[otherIndex]
        dao.putTemplate(first.copy(position = second.position))
        dao.putTemplate(second.copy(position = first.position))
        val key = activeDay().toString()
        dao.routine(key, first.id)?.let { dao.putRoutine(it.copy(position = second.position)) }
        dao.routine(key, second.id)?.let { dao.putRoutine(it.copy(position = first.position)) }
    }
}
