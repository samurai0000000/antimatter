/*
 * ConversationEntity.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val timestamp: Long,
    val agentId: String = "legacy",
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val stepCount: Int = 0
) {
    val lastModified: Long get() = timestamp
}
