package com.lanxin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lanxin.refactor.cloud.CloudConfig
import com.lanxin.refactor.cloud.OpenAiCompatibleCloudChatClient
import com.lanxin.refactor.settings.CloudSettingsStore
import kotlinx.coroutines.launch

@Composable
fun CloudSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CloudSettingsStore(context.applicationContext) }
    var baseUrl by remember { mutableStateOf(CloudConfig().baseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(CloudConfig().model) }
    var banner by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val c = store.current()
        baseUrl = c.baseUrl
        apiKey = c.apiKey
        model = c.model
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("云端设置")
        }
        if (banner.isNotEmpty()) Text(banner)
        Text("OpenAI 兼容 /v1/chat/completions。密钥仅存本机。")
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            singleLine = true
        )
        Button(
            onClick = {
                scope.launch {
                    store.save(baseUrl, apiKey, model)
                    banner = "已保存（configured=${apiKey.isNotBlank() && baseUrl.isNotBlank()}）"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
        Button(
            onClick = {
                scope.launch {
                    store.save(baseUrl, apiKey, model)
                    val cfg = CloudConfig(
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim().ifBlank { "gpt-4o-mini" }
                    )
                    val client = OpenAiCompatibleCloudChatClient(configProvider = { cfg })
                    if (!client.isConfigured) {
                        banner = "未配置完整"
                        return@launch
                    }
                    val r = client.chat(
                        systemPrompt = "你是简短助手，只回一个词：pong",
                        userMessage = "ping",
                        maxTokens = 32
                    )
                    banner = if (r.ok) "探测成功: ${r.text?.take(80)}"
                    else "探测失败: ${r.detail}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("探测连通") }
    }
}
