package com.example

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val mode: String,
    val reps: Int,
    val calories: Float,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<WorkoutSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WorkoutSessionEntity)
}

@Database(entities = [WorkoutSessionEntity::class], version = 1, exportSchema = false)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
}

class WorkoutRepository(private val workoutDao: WorkoutDao) {
    val allSessions: Flow<List<WorkoutSessionEntity>> = workoutDao.getAllSessions()

    suspend fun insert(session: WorkoutSessionEntity) = workoutDao.insertSession(session)
}

class WorkoutViewModel(private val repository: WorkoutRepository) : ViewModel() {
    val sessions: StateFlow<List<WorkoutSessionEntity>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun insert(session: WorkoutSessionEntity) = viewModelScope.launch {
        repository.insert(session)
    }
}
