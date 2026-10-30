package com.chalkie.app.data

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
import kotlinx.coroutines.flow.Flow

/** One line of the activity log (SPEC §13): talks, texts, calls, voice messages, network events. */
@Entity(tableName = "log")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    /** TALK, PRIVATE_CALL, TEXT, VOICE_MSG, ROLE, NETWORK */
    val kind: String,
    val who: String,
    val channel: Int? = null,
    val durationMs: Long? = null,
    val text: String? = null,
)

/** A voice message, sent (outbox until delivered) or received (inbox). */
@Entity(tableName = "voice_message")
data class VoiceMessageEntity(
    @PrimaryKey val uid: Long,
    val fromId: Long,
    val toId: Long,
    val peerName: String,
    val createdAt: Long,
    val codec: Int,
    /** VoiceMessage body: the same bytes that travel on the air. */
    val body: ByteArray,
    val outgoing: Boolean,
    val delivered: Boolean = false,
    val heard: Boolean = false,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(e: LogEntry)

    @Query("SELECT * FROM log ORDER BY timeMs DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<LogEntry>>

    @Query("SELECT * FROM log ORDER BY timeMs ASC")
    suspend fun all(): List<LogEntry>

    @Query("DELETE FROM log")
    suspend fun clear()
}

@Dao
interface VoiceMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: VoiceMessageEntity): Long

    @Query("SELECT * FROM voice_message ORDER BY createdAt DESC")
    fun all(): Flow<List<VoiceMessageEntity>>

    @Query("SELECT * FROM voice_message WHERE outgoing = 1 AND delivered = 0")
    suspend fun undelivered(): List<VoiceMessageEntity>

    @Query("UPDATE voice_message SET delivered = 1 WHERE uid = :uid")
    suspend fun markDelivered(uid: Long)

    @Query("UPDATE voice_message SET heard = 1 WHERE uid = :uid")
    suspend fun markHeard(uid: Long)

    @Query("DELETE FROM voice_message WHERE uid = :uid")
    suspend fun delete(uid: Long)
}

@Database(entities = [LogEntry::class, VoiceMessageEntity::class], version = 1, exportSchema = false)
abstract class ChalkieDb : RoomDatabase() {
    abstract fun log(): LogDao
    abstract fun voiceMessages(): VoiceMessageDao

    companion object {
        fun create(context: Context): ChalkieDb =
            Room.databaseBuilder(context, ChalkieDb::class.java, "chalkie.db").build()
    }
}
