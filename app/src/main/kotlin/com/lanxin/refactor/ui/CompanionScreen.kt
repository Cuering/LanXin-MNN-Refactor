package com.lanxin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lanxin.companion.LocalCompanionSession
import com.lanxin.core.memory.FileMemoryStore
import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.core.memory.MemoryItem
import com.lanxin.core.memory.MemoryType
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.MnnLocalLlmEngine
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun CompanionScreen(onOpenMemory: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        FileMemoryStore(File(context.filesDir, "memory/memories.json"))
    }
    val engine = remember { MnnLocalLlmEngine() }
    val session = remember {
        LocalCompanionSession(
            engine = engine,
            memoryEnricher = MemoryEnricher(store),
            memoryStore = store
        )
    }
    var modelPath by remember {
        mutableStateOf(
            File(context.getExternalFilesDir(null), "models/local-llm").absolutePath
        )
    }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("未加载") }
    val lines = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        // seed only if empty
        if (store.list(1).isEmpty()) {
            store.upsert(
                MemoryItem(
                    id = UUID.randomUUID().toString(),
                    content = "用户是哥哥，称呼亲近。",
                    type = MemoryType.FACTUAL,
                    importance = 0.8f
                )
            )
        }
    }

    fun stateText(s: EngineState): String = when (s) {
        is EngineState.Uninitialized -> "未初始化"
        is EngineState.Loading -> "加载中…"
        is EngineState.Ready -> "READY backend=${s.backendHint ?: "?"}"
        is EngineState.NativeMissing -> "NATIVE_MISSING: ${s.detail}"
        is EngineState.LoadFailed -> "LOAD_FAILED: ${s.detail}"
        is EngineState.Stub -> "STUB: ${s.reason}"
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(status)
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型目录") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    status = "加载中…"
                    val s = session.ensureLoaded(modelPath)
                    status = stateText(s)
                    lines.add("[系统] $status")
                }
            }) { Text("加载本地脑") }
            Button(onClick = {
                scope.launch {
                    val n = store.list(20).size
                    lines.add("[记忆] 当前活跃 $n 条")
                }
            }) { Text("记忆条数") }
            Button(onClick = onOpenMemory) { Text("记忆管理") }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(lines) { Text(it, Modifier.padding(vertical = 2.dp)) }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("对兰儿说") }
        )
        Button(
            onClick = {
                val msg = input.trim()
                if (msg.isEmpty()) return@Button
                input = ""
                lines.add("哥哥: $msg")
                scope.launch {
                    val r = session.chat(msg)
                    status = stateText(r.engineState)
                    lines.add("兰儿: ${r.reply}")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("发送") }
    }
}
