package com.lanxin.core.memory

import kotlinx.serialization.Serializable

/**
 * 与旧 LanXin memory 类型对齐的精简模型（无 Room 依赖，便于替换存储）。
 */
@Serializable
data class MemoryItem(
    val id: String,
    val content: String,
    val type: String = MemoryType.FACTUAL,
    val importance: Float = 0.5f,
    val status: String = MemoryStatus.ACTIVE,
    val lifecycle: String = MemoryLifecycle.NORMAL,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long? = null,
    val metadata: String? = null
)

object MemoryType {
    const val PREFERENCE = "preference"
    const val FACTUAL = "factual"
    const val DAILY = "daily"
    const val CHAT = "chat"
    const val INSIGHT = "insight"
    const val INSTRUCTION = "instruction"
    const val JUDGMENT = "judgment"
}

object MemoryStatus {
    const val ACTIVE = "active"
    const val ARCHIVED = "archived"
    const val EXPIRED = "expired"
}

object MemoryLifecycle {
    const val PERMANENT = "permanent"
    const val NORMAL = "normal"
    const val EPHEMERAL = "ephemeral"
}
