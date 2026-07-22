package com.lanxin.refactor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.lanxin.core.memory.FileMemoryStore
import com.lanxin.core.memory.MemoryImportExport
import com.lanxin.core.memory.MemoryItem
import com.lanxin.core.memory.MemoryType
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        FileMemoryStore(File(context.filesDir, "memory/memories.json"))
    }
    val items = remember { mutableStateListOf<MemoryItem>() }
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var banner by remember { mutableStateOf("") }

    suspend fun reload() {
        val list = if (query.isBlank()) store.list(100) else store.search(query, 50)
        items.clear()
        items.addAll(list)
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("记忆 (${items.size})")
        }
        if (banner.isNotEmpty()) Text(banner)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { reload() } }) { Text("刷新/搜索") }
            Button(onClick = {
                scope.launch {
                    val json = MemoryImportExport.exportJson(store.list(500))
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("memories", json))
                    banner = "已导出 ${json.length} 字符到剪贴板"
                }
            }) { Text("导出剪贴板") }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("新增记忆") }
        )
        Button(
            onClick = {
                val text = draft.trim()
                if (text.isEmpty()) return@Button
                scope.launch {
                    store.upsert(
                        MemoryItem(
                            id = UUID.randomUUID().toString(),
                            content = text,
                            type = MemoryType.FACTUAL,
                            importance = 0.7f
                        )
                    )
                    draft = ""
                    banner = "已添加"
                    reload()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("添加") }

        OutlinedTextField(
            value = importText,
            onValueChange = { importText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("粘贴 JSON 导入") },
            minLines = 2
        )
        Button(
            onClick = {
                scope.launch {
                    try {
                        val n = MemoryImportExport.mergeInto(store, importText)
                        importText = ""
                        banner = "导入 $n 条"
                        reload()
                    } catch (e: Exception) {
                        banner = "导入失败: ${e.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("合并导入") }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Card(Modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier = Modifier.padding(12.dp)) {
                        Text(item.content)
                        Text("type=${item.type} imp=${item.importance}")
                        TextButton(onClick = {
                            scope.launch {
                                store.delete(item.id)
                                banner = "已删除"
                                reload()
                            }
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}
