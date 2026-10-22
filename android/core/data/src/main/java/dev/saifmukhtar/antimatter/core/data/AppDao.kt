/*
 * AppDao.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM conversations WHERE agentId = :agentId ORDER BY timestamp DESC")
    fun getConversationsForAgentFlow(agentId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    fun getAllConversationsFlow(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Query("DELETE FROM agents")
    suspend fun deleteAllAgents()

    @Query("SELECT * FROM agents")
    fun getAllAgentsFlow(): Flow<List<AgentEntity>>


    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, timestamp = :timestamp, stepCount = :stepCount WHERE id = :id")
    suspend fun updateConversationMetadata(id: String, title: String, timestamp: Long, stepCount: Int)

    @Query("UPDATE conversations SET scrollIndex = :index, scrollOffset = :offset WHERE id = :id")
    suspend fun updateScrollState(id: String, index: Int, offset: Int)

    @Query("UPDATE conversations SET stepCount = :count WHERE id = :id")
    suspend fun updateStepCount(id: String, count: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<StepEntity>)

    @Query("SELECT * FROM steps WHERE conversationId = :conversationId ORDER BY stepIndex ASC")
    suspend fun getStepsForConversation(conversationId: String): List<StepEntity>

    @Query("DELETE FROM steps WHERE conversationId = :conversationId AND stepIndex >= :fromIndex")
    suspend fun deleteStepsFromIndex(conversationId: String, fromIndex: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifacts(artifacts: List<ArtifactEntity>)

    @Query("SELECT * FROM artifacts WHERE conversationId = :conversationId ORDER BY name ASC")
    suspend fun getArtifactsForConversation(conversationId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifacts WHERE conversationId = :conversationId AND path = :path")
    suspend fun getArtifact(conversationId: String, path: String): ArtifactEntity?

}
