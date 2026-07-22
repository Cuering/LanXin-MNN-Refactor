package com.lanxin.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 对话历史持久化抽象。
 * 与 [MemoryStore] 分离：历史是短窗口会话态，记忆是长期知识。
 */
interface ConversationHistoryStore {
    suspend fun load(): List<ConversationTurn>
    suspend fun save(turns: List<ConversationTurn>)
    suspend fun clear()
}

/** 进程内，单测用。 */
class InMemoryConversationHistoryStore : ConversationHistoryStore {
    @Volatile
    private var turns: List<ConversationTurn> = emptyList()

    override suspend fun load(): List<ConversationTurn> = turns

    override suspend fun save(turns: List<ConversationTurn>) {
        this.turns = turns.toList()
    }

    override suspend fun clear() {
        turns = emptyList()
    }
}

/**
 * JSON 文件持久化对话历史。
 * 默认路径建议：`filesDir/memory/conversation_history.json`
 */
class FileConversationHistoryStore(
    private val file: File
) : ConversationHistoryStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    override suspend fun load(): List<ConversationTurn> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            try {
                val text = file.readText()
                if (text.isBlank()) emptyList()
                else json.decodeFromString(ListSerializer(ConversationTurn.serializer()), text)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override suspend fun save(turns: List<ConversationTurn>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(ListSerializer(ConversationTurn.serializer()), turns)
            )
        }
    }

    override suspend fun clear() = save(emptyList())
}
