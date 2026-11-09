package com.toolbox.multitool.notes

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "maps")
data class MindMap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Every node can have any number of children, to any depth. parentId == null marks a map's root. */
@Entity(tableName = "nodes", indices = [Index("mapId"), Index("parentId")])
data class MindNode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapId: Long,
    val parentId: Long?,
    val text: String,
    val note: String = "",
    val collapsed: Boolean = false,
    val position: Int = 0,
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun notes(): Flow<List<Note>>
    @Upsert suspend fun save(n: Note): Long
    @Delete suspend fun delete(n: Note)
}

@Dao
interface MapDao {
    @Query("SELECT * FROM maps ORDER BY updatedAt DESC") fun maps(): Flow<List<MindMap>>
    @Insert suspend fun insert(m: MindMap): Long
    @Update suspend fun update(m: MindMap)
    @Query("DELETE FROM maps WHERE id = :id") suspend fun deleteMap(id: Long)
    @Query("DELETE FROM nodes WHERE mapId = :id") suspend fun deleteNodesOf(id: Long)

    @Query("SELECT * FROM nodes WHERE mapId = :mapId ORDER BY position, id") fun nodes(mapId: Long): Flow<List<MindNode>>
    @Query("SELECT * FROM nodes WHERE mapId = :mapId") suspend fun nodesOnce(mapId: Long): List<MindNode>
    @Insert suspend fun insertNode(n: MindNode): Long
    @Update suspend fun updateNode(n: MindNode)
    @Query("DELETE FROM nodes WHERE id IN (:ids)") suspend fun deleteNodes(ids: List<Long>)
}

@Database(entities = [Note::class, MindMap::class, MindNode::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun maps(): MapDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "multitool.db").build().also { inst = it }
        }
    }
}
