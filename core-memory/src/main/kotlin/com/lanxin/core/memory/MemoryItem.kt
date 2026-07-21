package com.lanxin.core.memory

import kotlinx.serialization.Serializable

@Serializable
data class MemoryItem(
    val id: String,
    val content: String,
    val importance: Float = 0.5f,
    val tags: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)
