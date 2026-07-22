package com.lanxin.refactor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lanxin.companion.ConversationHistory
import com.lanxin.companion.FileConversationHistoryStore
import com.lanxin.companion.LocalCompanionSession
import com.lanxin.core.memory.FileMemoryStore
import com.lanxin.core.memory.MemoryEnricher
import com.lanxin.core.memory.MemoryItem
import com.lanxin.core.memory.MemoryType
import com.lanxin.localllm.domain.ChatRoutePolicy
import com.lanxin.localllm.domain.CloudChatClient
import com.lanxin.localllm.domain.EngineState
import com.lanxin.localllm.domain.MnnLocalLlmEngine
import com.lanxin.localllm.domain.StubCloudChatClient
import com.lanxin.localllm.domain.UnconfiguredCloudChatClient
import com.lanxin.refactor.cloud.CloudConfig
import com.lanxin.refactor.cloud.OpenAiCompatibleCloudChatClient
import com.lanxin.refactor.settings.CloudSettingsStore
import com.lanxin.voice.PcmAudioRecorder
import com.lanxin.voice.VoiceModelPaths
import com.lanxin.voice.pet.Live2DWebViewHost
import com.lanxin.voice.sherpa.SherpaAsrEngine
import com.lanxin.voice.sherpa.SherpaTtsEngine
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Composable
fun CompanionScreen(
    onOpenMemory: () -> Unit = {},
    onOpenCloud: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember {
        FileMemoryStore(File(context.filesDir, "memory/memories.json"))
    }
    val historyStore = remember {
        FileConversationHistoryStore(File(context.filesDir, "memory/conversation_history.json"))
    }
    val conversationHistory = remember { ConversationHistory(maxTurns = 20) }
    val cloudSettings = remember { CloudSettingsStore(context.applicationContext) }
    val cloudConfigRef = remember { AtomicReference(CloudConfig()) }
    val realCloudClient = remember {
        OpenAiCompatibleCloudChatClient(configProvider = { cloudConfigRef.get() })
    }
    val engine = remember { MnnLocalLlmEngine() }
    var policy by remember { mutableStateOf(ChatRoutePolicy.PREFER_LOCAL) }
    var cloudMode by remember { mutableStateOf("none") }

    // 全真引擎打进包：MNN + Sherpa ASR/TTS + 麦 + Live2D 壳（无模型时状态诚实，不伪装 READY）
    val asr = remember { SherpaAsrEngine() }
    val tts = remember { SherpaTtsEngine() }
    val mic = remember { PcmAudioRecorder() }
    val petHost = remember { Live2DWebViewHost() }
    var cloudConfigured by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("语音未加载") }
    var micStatus by remember { mutableStateOf("麦:空闲") }
    var petStatus by remember { mutableStateOf("宠物:未挂载") }
    var recording by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<String>() }

    fun addLine(s: String) {
        lines.add(s)
    }

    val filesBase = remember {
        context.getExternalFilesDir(null) ?: context.filesDir
    }
    var asrPath by remember {
        mutableStateOf(
            VoiceModelPaths.resolveAsrDir(filesBase)
                ?: VoiceModelPaths.defaultAsrDir(filesBase)
        )
    }
    var ttsPath by remember {
        mutableStateOf(
            VoiceModelPaths.resolveTtsDir(filesBase)
                ?: VoiceModelPaths.defaultTtsDir(filesBase)
        )
    }
    var modelPath by remember {
        mutableStateOf(File(filesBase, "models/local-llm").absolutePath)
    }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("未加载") }

    fun resolveCloud(): CloudChatClient = when (cloudMode) {
        "stub" -> StubCloudChatClient()
        "real" -> realCloudClient
        else -> UnconfiguredCloudChatClient()
    }

    // 复用同一会话实例（引擎/历史/store），仅在路由/云模式变化时重建
    val session = remember(policy, cloudMode) {
        LocalCompanionSession(
            engine = engine,
            memoryEnricher = MemoryEnricher(store),
            memoryStore = store,
            routePolicy = policy,
            cloudClient = resolveCloud(),
            asrEngine = asr,
            ttsEngine = tts,
            autoSpeak = true,
            conversationHistory = conversationHistory,
            historyStore = historyStore
        )
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micStatus = if (granted) "麦:已授权" else "麦:权限拒绝"
        addLine(if (granted) "[麦] RECORD_AUDIO 已授予" else "[麦] 权限被拒")
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                runCatching { mic.cancelRecording() }
                runCatching { tts.stop() }
            }
            petHost.detach()
        }
    }

    LaunchedEffect(Unit) {
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
        val histN = session.loadHistory()
        if (histN > 0) {
            addLine("[历史] 已恢复 $histN 条对话")
            session.historyTurns.takeLast(6).forEach { t ->
                addLine("${t.role}: ${t.content}")
            }
        }
        val asrState = asr.load(asrPath)
        val ttsState = tts.load(ttsPath)
        voiceStatus = "ASR=${asrState.shortLabel} TTS=${ttsState.shortLabel}"
        addLine("[引擎] MNN=已打包 JNI | Sherpa ASR/TTS=已打包 so")
        addLine("[语音] $voiceStatus")
        addLine("[语音路径] asr=$asrPath")
        addLine("[语音路径] tts=$ttsPath")
        if (!hasMicPermission()) {
            addLine("[麦] 未授权，点「要麦权」申请")
        } else {
            micStatus = "麦:已授权"
        }
        cloudSettings.configFlow.collect { cfg ->
            cloudConfigRef.set(cfg)
            cloudConfigured = cfg.apiKey.isNotBlank() && cfg.baseUrl.isNotBlank()
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

    suspend fun runVoiceTurn(pcm: ByteArray?, hint: String?, label: String) {
        petHost.postExpression("speak")
        val r = session.chatFromVoice(hintText = hint, pcm16le = pcm)
        status = stateText(r.engineState)
        val ttsInfo = r.tts?.let { " tts=${it.detail ?: "ok"}(${it.spokenChars})" } ?: ""
        addLine("兰儿[$label/${r.backend}]$ttsInfo: ${r.reply}")
        // P9：优先 PCM RMS 驱动嘴型；无 PCM 时按时长占位
        val ttsR = r.tts
        if (ttsR != null && ttsR.ok) {
            petHost.lipSyncFromPcm(
                pcm16le = ttsR.pcm16le,
                sampleRateHz = ttsR.pcmSampleRate,
                durationMsFallback = ttsR.audioDurationMs
            )
        } else {
            petHost.postMouthOpen(0f)
        }
        petHost.postExpression("idle")
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(status)
        Text(
            "路由=$policy 云=$cloudMode cfg=$cloudConfigured | " +
                "ASR=${asr.state.shortLabel} TTS=${tts.state.shortLabel} | 历史=${session.historyTurns.size}"
        )
        Text("$micStatus | $petStatus | ${petHost.state.shortLabel}")

        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).also { container ->
                        container.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        petHost.attach(ctx, container)
                        petStatus = "宠物:已挂载"
                        addLine("[宠物] Live2D 壳 ${petHost.state.shortLabel}")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("LLM 模型目录") },
            singleLine = true
        )
        OutlinedTextField(
            value = asrPath,
            onValueChange = { asrPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ASR 模型目录") },
            singleLine = true
        )
        OutlinedTextField(
            value = ttsPath,
            onValueChange = { ttsPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("TTS 模型目录") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = policy == ChatRoutePolicy.PREFER_LOCAL,
                onClick = { policy = ChatRoutePolicy.PREFER_LOCAL },
                label = { Text("本地优先") }
            )
            FilterChip(
                selected = policy == ChatRoutePolicy.LOCAL_ONLY,
                onClick = { policy = ChatRoutePolicy.LOCAL_ONLY },
                label = { Text("仅本地") }
            )
            FilterChip(
                selected = policy == ChatRoutePolicy.PREFER_CLOUD,
                onClick = { policy = ChatRoutePolicy.PREFER_CLOUD },
                label = { Text("云优先") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = cloudMode == "none",
                onClick = { cloudMode = "none" },
                label = { Text("云关") }
            )
            FilterChip(
                selected = cloudMode == "stub",
                onClick = { cloudMode = "stub" },
                label = { Text("云stub") }
            )
            FilterChip(
                selected = cloudMode == "real",
                onClick = { cloudMode = "real" },
                label = { Text("云真实") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                scope.launch {
                    status = "加载中…"
                    val s = session.ensureLoaded(modelPath)
                    status = stateText(s)
                    addLine("[系统] $status")
                }
            }) { Text("加载本地脑") }
            Button(onClick = {
                scope.launch {
                    addLine("[语音] 重新加载…")
                    val asrState = asr.load(asrPath)
                    val ttsState = tts.load(ttsPath)
                    voiceStatus = "ASR=${asrState.shortLabel} TTS=${ttsState.shortLabel}"
                    addLine(
                        "[语音] $voiceStatus nativeAsr=${asr.isUsingNative} nativeTts=${tts.isUsingNative}"
                    )
                }
            }) { Text("加载语音") }
            Button(onClick = {
                if (hasMicPermission()) {
                    micStatus = "麦:已授权"
                    addLine("[麦] 已有权限")
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) { Text("要麦权") }
            Button(onClick = {
                scope.launch {
                    session.clearHistory()
                    addLine("[历史] 已清空")
                }
            }) { Text("清历史") }
            Button(onClick = onOpenMemory) { Text("记忆") }
            Button(onClick = onOpenCloud) { Text("云设置") }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    val msg = input.trim()
                    if (msg.isEmpty()) return@Button
                    input = ""
                    addLine("哥哥: $msg")
                    scope.launch {
                        petHost.postExpression("speak")
                        val r = session.chat(msg)
                        status = stateText(r.engineState)
                        val ttsInfo =
                            r.tts?.let { " tts=${it.detail ?: "ok"}(${it.spokenChars})" } ?: ""
                        addLine("兰儿[${r.backend}/${r.routeReason}]$ttsInfo: ${r.reply}")
                        val ttsR = r.tts
                        if (ttsR != null && ttsR.ok) {
                            petHost.lipSyncFromPcm(
                                pcm16le = ttsR.pcm16le,
                                sampleRateHz = ttsR.pcmSampleRate,
                                durationMsFallback = ttsR.audioDurationMs
                            )
                        } else {
                            petHost.postMouthOpen(0f)
                        }
                        petHost.postExpression("idle")
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("发送") }
            Button(
                onClick = {
                    val msg = input.trim()
                    if (msg.isEmpty()) return@Button
                    input = ""
                    addLine("哥哥(语音hint): $msg")
                    scope.launch { runVoiceTurn(pcm = null, hint = msg, label = "hint") }
                },
                modifier = Modifier.weight(1f)
            ) { Text("语音hint") }
            Button(
                onClick = {
                    scope.launch {
                        if (!recording) {
                            if (!hasMicPermission()) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                addLine("[麦] 请先授权再按开始录音")
                                return@launch
                            }
                            val started = mic.startRecording()
                            if (started.isFailure) {
                                micStatus = "麦:启动失败"
                                addLine("[麦] start失败: ${started.exceptionOrNull()?.message}")
                                return@launch
                            }
                            recording = true
                            micStatus = "麦:录音中…"
                            addLine("[麦] 录音中，再点「停麦发送」")
                        } else {
                            val stopped = mic.stopRecording()
                            recording = false
                            if (stopped.isFailure) {
                                micStatus = "麦:停止失败"
                                addLine("[麦] stop失败: ${stopped.exceptionOrNull()?.message}")
                                return@launch
                            }
                            val audio = stopped.getOrNull()!!
                            micStatus =
                                "麦:已录 ${audio.durationMs}ms ${audio.byteCount}B stub=${audio.isStub}"
                            addLine(
                                "[麦] PCM ${audio.byteCount}B ${audio.sampleRateHz}Hz " +
                                    "${audio.durationMs}ms stub=${audio.isStub}"
                            )
                            runVoiceTurn(
                                pcm = audio.pcm16leMono,
                                hint = input.trim().ifBlank { null },
                                label = "mic"
                            )
                            input = ""
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (recording) "停麦发送" else "开始录音") }
        }
    }
}
