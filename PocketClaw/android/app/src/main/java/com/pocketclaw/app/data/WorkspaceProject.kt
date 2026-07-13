package com.pocketclaw.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A project workspace that binds a local directory to PocketClaw's AI context.
 * Files inside [localFolderPath] are automatically loaded as text attachments
 * when the workspace is active in [com.pocketclaw.app.ui.chat.ChatViewModel].
 */
@Entity(tableName = "workspace_projects")
data class WorkspaceProject(
    @PrimaryKey val id: String,
    val name: String,
    val localFolderPath: String,
    val boundSkillIds: String = "",
    val projectInstructions: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspace_projects ORDER BY createdAt DESC")
    fun allProjects(): Flow<List<WorkspaceProject>>

    @Query("SELECT * FROM workspace_projects WHERE id = :id")
    suspend fun getById(id: String): WorkspaceProject?

    @Upsert
    suspend fun upsert(project: WorkspaceProject)

    @Query("DELETE FROM workspace_projects WHERE id = :id")
    suspend fun delete(id: String)
}
