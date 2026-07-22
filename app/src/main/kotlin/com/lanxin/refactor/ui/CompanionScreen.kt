package com.lanxin.refactor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.lanxin.refactor.paths.LanXinPaths
import com.lanxin.refactor.settings.CloudSettingsStore
import com.lanxin.localllm.domain.ReplySanitizer
import com.lanxin.voice.PcmAudioRecorder
import com.lanxin.voice.VadAutoStopRecorder
import com.lanxin.voice.VoiceModelPaths
import com.lanxin.voice.pet.Live2DWebViewHost
import com.lanxin.voice.sherpa.SherpaAsrEngine
import com.lanxin.voice.sherpa.SherpaTtsEngine
import kotlinx.coroutines.Job
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
    val vadMic = remember { VadAutoStopRecorder(mic) }
    val petHost = remember { Live2DWebViewHost() }
    var cloudConfigured by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("\u8bed\u97f3\u672a\u52a0\u8f7d") }
    var micStatus by remember { mutableStateOf("\u9ea6:\u7a7a\u95f2") }
    var petStatus by remember { mutableStateOf("\u5ba0\u7269:\u672a\u6302\u8f7d") }
    var recording by remember { mutableStateOf(false) }
    var vadEnabled by remember { mutableStateOf(true) }
    var fullScreenPet by remember { mutableStateOf(false) }
    var vadJob by remember { mutableStateOf<Job?>(null) }
    val lines = remember { mutableStateListOf<String>() }

    fun addLine(s: String) {
        lines.add(s)
    }

    // 与旧 LanXin-Android 对齐：优先 /sdcard/LanXin/，回退 app 外存私有目录
    val filesBase = remember {
        LanXinPaths.resolveBaseDir(context)
    }
    val lanXinDir = remember(filesBase) {
        File(filesBase, LanXinPaths.ROOT_DIR).also {
            // 尝试建目录骨架（公共存储可能失败，静默忽略）
            LanXinPaths.ensureStructure(filesBase)
        }
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
        mutableStateOf(LanXinPaths.resolveLocalLlmPath(context).absolutePath)
    }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("\u672a\u52a0\u8f7d") }

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
        micStatus = if (granted) "\u9ea6:\u5df2\u6388\u6743" else "\u9ea6:\u6743\u9650\u62d2\u7edd"
        addLine(if (granted) "[\u9ea6] RECORD_AUDIO \u5df2\u6388\u4e88" else "[\u9ea6] \u6743\u9650\u88ab\u62d2")
    }

    DisposableEffect(Unit) {
        onDispose {
            vadJob?.cancel()
            scope.launch {
                runCatching { vadMic.cancel() }
                runCatching { mic.cancelRecording() }
                runCatching { tts.stop() }
            }
            petHost.detach()
        }
    }

    fun stateText(s: EngineState): String = when (s) {
        is EngineState.Uninitialized -> "\u672a\u521d\u59cb\u5316"
        is EngineState.Loading -> "\u52a0\u8f7d\u4e2d\u2026"
        is EngineState.Ready -> "READY backend=${s.backendHint ?: "?"}"
        is EngineState.NativeMissing -> "NATIVE_MISSING: ${s.detail}"
        is EngineState.LoadFailed -> "LOAD_FAILED: ${s.detail}"
        is EngineState.Stub -> "STUB: ${s.reason}"
    }

    /** \u7eaf\u672c\u5730\u53ef\u72ec\u7acb\u7528\uff1a\u672a READY \u65f6\u5148\u5c1d\u8bd5\u52a0\u8f7d\u672c\u5730\u6a21\u578b\uff08\u4e91\u7aef\u672a\u914d\u7f6e\u4e5f\u80fd\u804a\uff09\u3002 */
    suspend fun ensureLocalReady(): EngineState {
        val cur = engine.state
        if (cur is EngineState.Ready) return cur
        status = "\u52a0\u8f7d\u672c\u5730\u8111\u2026"
        val s = session.ensureLoaded(modelPath)
        status = stateText(s)
        addLine("[\u7cfb\u7edf] \u81ea\u52a8\u52a0\u8f7d: $status")
        return s
    }

    /** TTS 未 READY 时按当前路径再 load 一次（模型在手机 LanXin/tts/）。 */
    suspend fun ensureTtsReady(): Boolean {
        if (tts.state.isUsable) return true
        addLine("[TTS] \u672a READY\uff0c\u52a0\u8f7d $ttsPath")
        val st = tts.load(ttsPath)
        voiceStatus = "ASR=${asr.state.shortLabel} TTS=${st.shortLabel}"
        addLine("[TTS] ${st.shortLabel} native=${tts.isUsingNative}")
        return st.isUsable
    }

    suspend fun runVoiceTurn(pcm: ByteArray?, hint: String?, label: String) {
        ensureLocalReady()
        ensureTtsReady()
        petHost.postExpression("speak")
        val r = session.chatFromVoice(hintText = hint, pcm16le = pcm)
        status = stateText(r.engineState)
        val ttsInfo = r.tts?.let { " tts=${it.detail ?: "ok"}(${it.spokenChars})" } ?: " tts=skipped"
        addLine("\u5170\u513f[$label/${r.backend}]$ttsInfo: ${r.reply}")
        // P9：优先 PCM RMS \u9a71\u52a8\u5634\u578b\uff1b\u65e0 PCM \u65f6\u6309\u65f6\u957f\u5360\u4f4d
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

    suspend fun finishMicAudio(
        audio: com.lanxin.voice.RecordedAudio,
        viaVad: Boolean
    ) {
        val tag = if (viaVad) "VAD\u505c" else "\u624b\u505c"
        micStatus =
            "\u9ea6:$tag ${audio.durationMs}ms ${audio.byteCount}B stub=${audio.isStub}"
        addLine(
            "[\u9ea6] $tag PCM ${audio.byteCount}B ${audio.sampleRateHz}Hz " +
                "${audio.durationMs}ms stub=${audio.isStub}"
        )
        vadMic.lastSnapshot?.let { addLine("[VAD] ${it.shortLabel}") }
        runVoiceTurn(
            pcm = audio.pcm16leMono,
            hint = input.trim().ifBlank { null },
            label = if (viaVad) "mic-vad" else "mic"
        )
        input = ""
    }

    fun toggleMic() {
        scope.launch {
            if (!recording) {
                if (!hasMicPermission()) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    addLine("[\u9ea6] \u8bf7\u5148\u6388\u6743\u518d\u6309\u5f00\u59cb\u5f55\u97f3")
                    return@launch
                }
                val started = if (vadEnabled) {
                    vadMic.start()
                } else {
                    mic.startRecording()
                }
                if (started.isFailure) {
                    micStatus = "\u9ea6:\u542f\u52a8\u5931\u8d25"
                    addLine("[\u9ea6] start\u5931\u8d25: ${started.exceptionOrNull()?.message}")
                    return@launch
                }
                recording = true
                if (vadEnabled) {
                    micStatus = "\u9ea6:\u5f55\u97f3\u4e2d\u00b7VAD\u542c\u9759\u97f3\u2026"
                    addLine("[\u9ea6] VAD \u5f00\uff1a\u8bf4\u5b8c\u81ea\u52a8\u505c\u9ea6\u53d1\u9001\uff08\u4e5f\u53ef\u624b\u70b9\u505c\uff09")
                    vadJob?.cancel()
                    vadJob = scope.launch {
                        val stopped = vadMic.awaitAutoStop()
                        if (!recording) return@launch
                        recording = false
                        if (stopped.isFailure) {
                            // \u53ef\u80fd\u88ab\u624b\u52a8 stop \u62a2\u5148
                            val msg = stopped.exceptionOrNull()?.message.orEmpty()
                            if (msg != "not_recording") {
                                micStatus = "\u9ea6:VAD\u505c\u5931\u8d25"
                                addLine("[\u9ea6] VAD stop\u5931\u8d25: $msg")
                            }
                            return@launch
                        }
                        finishMicAudio(stopped.getOrNull()!!, viaVad = true)
                    }
                } else {
                    micStatus = "\u9ea6:\u5f55\u97f3\u4e2d\u2026"
                    addLine("[\u9ea6] \u5f55\u97f3\u4e2d\uff0c\u518d\u70b9\u300c\u505c\u9ea6\u53d1\u9001\u300d")
                }
            } else {
                // \u624b\u52a8\u505c\uff1a\u53d6\u6d88 VAD \u7b49\u5f85\uff0c\u76f4\u63a5 stop
                vadJob?.cancel()
                vadJob = null
                val stopped = if (vadEnabled) {
                    vadMic.stopManual()
                } else {
                    mic.stopRecording()
                }
                recording = false
                if (stopped.isFailure) {
                    micStatus = "\u9ea6:\u505c\u6b62\u5931\u8d25"
                    addLine("[\u9ea6] stop\u5931\u8d25: ${stopped.exceptionOrNull()?.message}")
                    return@launch
                }
                finishMicAudio(stopped.getOrNull()!!, viaVad = false)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (store.list(1).isEmpty()) {
            store.upsert(
                MemoryItem(
                    id = UUID.randomUUID().toString(),
                    content = "\u7528\u6237\u662f\u54e5\u54e5\uff0c\u79f0\u547c\u4eb2\u8fd1\u3002",
                    type = MemoryType.FACTUAL,
                    importance = 0.8f
                )
            )
        }
        val histN = session.loadHistory()
        if (histN > 0) {
            addLine("[\u5386\u53f2] \u5df2\u6062\u590d $histN \u6761\u5bf9\u8bdd")
            session.historyTurns.takeLast(6).forEach { t ->
                addLine("${t.role}: ${t.content}")
            }
        }
        val asrState = asr.load(asrPath)
        val ttsState = tts.load(ttsPath)
        voiceStatus = "ASR=${asrState.shortLabel} TTS=${ttsState.shortLabel}"
        addLine("[\u5f15\u64ce] MNN=\u5df2\u6253\u5305 JNI | Sherpa ASR/TTS=\u5df2\u6253\u5305 so")
        addLine("[\u8bed\u97f3] $voiceStatus nativeAsr=${asr.isUsingNative} nativeTts=${tts.isUsingNative}")
        addLine("[\u8bed\u97f3\u8def\u5f84] asr=$asrPath")
        addLine("[\u8bed\u97f3\u8def\u5f84] tts=$ttsPath looks=${VoiceModelPaths.looksLikeTtsModel(java.io.File(ttsPath))}")
        if (!hasMicPermission()) {
            addLine("[\u9ea6] \u672a\u6388\u6743\uff0c\u70b9\u300c\u8981\u9ea6\u6743\u300d\u7533\u8bf7")
        } else {
            micStatus = "\u9ea6:\u5df2\u6388\u6743"
        }
        cloudSettings.configFlow.collect { cfg ->
            cloudConfigRef.set(cfg)
            cloudConfigured = cfg.apiKey.isNotBlank() && cfg.baseUrl.isNotBlank()
        }
    }

    if (fullScreenPet) {
        FullScreenPetOverlay(
            petHost = petHost,
            statusLine = "\u8def\u7531=$policy | ${asr.state.shortLabel}/${tts.state.shortLabel} | VAD=${if (vadEnabled) "\u5f00" else "\u5173"}",
            micStatus = micStatus,
            recording = recording,
            vadEnabled = vadEnabled,
            onToggleVad = {
                if (!recording) {
                    vadEnabled = !vadEnabled
                    addLine("[VAD] ${if (vadEnabled) "\u5df2\u5f00\u542f\u81ea\u52a8\u505c\u9ea6" else "\u5df2\u5173\u95ed\uff08\u624b\u52a8\u505c\u9ea6\uff09"}")
                }
            },
            onMicClick = { toggleMic() },
            onExit = {
                fullScreenPet = false
                petStatus = "\u5ba0\u7269:\u5df2\u6302\u8f7d"
                addLine("[\u5ba0\u7269] \u9000\u51fa\u5168\u5c4f")
            }
        )
    } else {
        val scanRoots = remember { LanXinPaths.baseDirCandidates(context) }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ===== \u9876\u90e8\u72b6\u6001\u6761 =====
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "\u5170\u513f \u00b7 ${status}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "$micStatus | VAD=${if (vadEnabled) "\u5f00" else "\u5173"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // ===== \u53ef\u6eda\u52a8\u5185\u5bb9 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // \u2500\u2500 1. \u966a\u4f34\u5f62\u8c61\u5361\u7247 \u2500\u2500
                Card(
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            "${petHost.state.shortLabel} \u00b7 ${tts.state.shortLabel} \u00b7 ${asr.state.shortLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    FrameLayout(ctx).also { container ->
                                        container.layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        petHost.attach(ctx, container)
                                        petStatus = "\u5ba0\u7269:\u5df2\u6302\u8f7d"
                                        addLine("[\u5ba0\u7269] Live2D \u58f3 ${petHost.state.shortLabel}")
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    fullScreenPet = true
                                    petStatus = "\u5ba0\u7269:\u5168\u5c4f"
                                    addLine("[\u5ba0\u7269] \u8fdb\u5165\u5168\u5c4f")
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("\u5168\u5c4f\u5f62\u8c61") }
                            OutlinedButton(
                                onClick = onOpenMemory,
                                modifier = Modifier.weight(1f)
                            ) { Text("\u8bb0\u5fc6") }
                            OutlinedButton(
                                onClick = onOpenCloud,
                                modifier = Modifier.weight(1f)
                            ) { Text("\u4e91\u8bbe\u7f6e") }
                        }
                    }
                }

                // \u2500\u2500 2. \u6a21\u578b\u8def\u5f84\uff08\u6587\u4ef6\u5939\u9009\u62e9\uff09 \u2500\u2500
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "\u6a21\u578b\u8def\u5f84",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        ModelPathPickerRow(
                            title = "\u2728 LLM \u6a21\u578b",
                            path = modelPath,
                            scanRoots = scanRoots,
                            onPathPicked = { modelPath = it }
                        )
                        ModelPathPickerRow(
                            title = "\ud83c\udfa4 ASR \u8bed\u97f3\u8bc6\u522b",
                            path = asrPath,
                            scanRoots = scanRoots,
                            onPathPicked = { asrPath = it }
                        )
                        ModelPathPickerRow(
                            title = "\ud83d\udd0a TTS \u8bed\u97f3\u5408\u6210",
                            path = ttsPath,
                            scanRoots = scanRoots,
                            onPathPicked = { ttsPath = it }
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        status = "\u52a0\u8f7d\u4e2d\u2026"
                                        val s = session.ensureLoaded(modelPath)
                                        status = stateText(s)
                                        addLine("[\u7cfb\u7edf] $status")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("\u52a0\u8f7d\u672c\u5730\u8111") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        addLine("[\u8bed\u97f3] \u91cd\u65b0\u52a0\u8f7d\u2026")
                                        val asrState = asr.load(asrPath)
                                        val ttsState = tts.load(ttsPath)
                                        voiceStatus = "ASR=${asrState.shortLabel} TTS=${ttsState.shortLabel}"
                                        addLine("[\u8bed\u97f3] $voiceStatus nativeAsr=${asr.isUsingNative} nativeTts=${tts.isUsingNative}")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("\u52a0\u8f7d\u8bed\u97f3") }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    ensureTtsReady()
                                    val sample = input.trim().ifBlank {
                                        "\u54e5\u54e5\u597d\uff0c\u6211\u662f\u5170\u513f\u3002\u8fd9\u662f\u8bed\u97f3\u56de\u590d\u8bd5\u542c\u3002"
                                    }
                                    val speech = ReplySanitizer.forSpeech(sample)
                                    addLine("[\u8bd5\u542c] \u64ad\u62a5: $speech")
                                    petHost.postExpression("speak")
                                    val r = tts.speak(speech)
                                    addLine(
                                        "[\u8bd5\u542c] ok=${r.ok} detail=${r.detail} " +
                                            "chars=${r.spokenChars} dur=${r.audioDurationMs}ms " +
                                            "native=${tts.isUsingNative} state=${tts.state.shortLabel}"
                                    )
                                    if (r.ok) {
                                        petHost.lipSyncFromPcm(
                                            pcm16le = r.pcm16le,
                                            sampleRateHz = r.pcmSampleRate,
                                            durationMsFallback = r.audioDurationMs
                                        )
                                    }
                                    petHost.postExpression("idle")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("\u8bd5\u542c TTS\uff08\u7528\u624b\u673a\u5185 Sherpa \u6a21\u578b\uff09") }
                    }
                }

                // \u2500\u2500 3. \u8def\u7531\u9009\u62e9 \u2500\u2500
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "\u8def\u7531\u7b56\u7565",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = policy == ChatRoutePolicy.PREFER_LOCAL,
                                onClick = { policy = ChatRoutePolicy.PREFER_LOCAL },
                                label = { Text("\u672c\u5730\u4f18\u5148") }
                            )
                            FilterChip(
                                selected = policy == ChatRoutePolicy.LOCAL_ONLY,
                                onClick = { policy = ChatRoutePolicy.LOCAL_ONLY },
                                label = { Text("\u4ec5\u672c\u5730") }
                            )
                            FilterChip(
                                selected = policy == ChatRoutePolicy.PREFER_CLOUD,
                                onClick = { policy = ChatRoutePolicy.PREFER_CLOUD },
                                label = { Text("\u4e91\u4f18\u5148") }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = cloudMode == "none",
                                onClick = { cloudMode = "none" },
                                label = { Text("\u4e91\u5173") }
                            )
                            FilterChip(
                                selected = cloudMode == "stub",
                                onClick = { cloudMode = "stub" },
                                label = { Text("\u4e91stub") }
                            )
                            FilterChip(
                                selected = cloudMode == "real",
                                onClick = { cloudMode = "real" },
                                label = { Text("\u4e91\u771f\u5b9e") }
                            )
                        }
                    }
                }

                // \u2500\u2500 4. \u5bf9\u8bdd\u65e5\u5fd7 \u2500\u2500
                if (lines.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "\u65e5\u5fd7 \u00b7 ${lines.size} \u6761",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            lines.takeLast(40).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // \u5e95\u90e8\u5b89\u5168\u7a7a\u9699
                Spacer(Modifier.height(80.dp))
            }

            // ===== \u5e95\u90e8\u8f93\u5165\u533a\uff08\u56fa\u5b9a\uff09 =====
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // \u8f93\u5165\u6846
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("\u5bf9\u5170\u513f\u8bf4\u2026") },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp)
                        )
                        Button(
                            onClick = {
                                val msg = input.trim()
                                if (msg.isEmpty()) return@Button
                                input = ""
                                addLine("\u54e5\u54e5: $msg")
                                scope.launch {
                                    ensureLocalReady()
                                    ensureTtsReady()
                                    petHost.postExpression("speak")
                                    val r = session.chat(msg)
                                    status = stateText(r.engineState)
                                    val ttsInfo = r.tts?.let { " tts=${it.detail ?: "ok"}(${it.spokenChars})" } ?: " tts=skipped"
                                    addLine("\u5170\u513f[${r.backend}/${r.routeReason}]$ttsInfo: ${r.reply}")
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
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                        ) { Text("\u53d1\u9001") }
                    }
                    // \u5e95\u90e8\u6309\u94ae\u884c
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { toggleMic() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (recording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when {
                                    recording && vadEnabled -> "\u23fa \u5f55\u97f3\u4e2d\u00b7VAD"
                                    recording -> "\u23fa \u505c\u9ea6\u53d1\u9001"
                                    else -> "\ud83c\udf99 \u5f00\u59cb\u5f55\u97f3"
                                },
                                maxLines = 1
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (!recording) {
                                    vadEnabled = !vadEnabled
                                    addLine("[VAD] ${if (vadEnabled) "\u5df2\u5f00\u542f\u81ea\u52a8\u505c\u9ea6" else "\u5df2\u5173\u95ed\uff08\u624b\u52a8\u505c\u9ea6\uff09"}")
                                }
                            },
                            modifier = Modifier.weight(0.6f)
                        ) { Text(if (vadEnabled) "VAD \u81ea\u52a8\u505c" else "VAD \u5173") }
                        OutlinedButton(onClick = {
                            scope.launch { session.clearHistory(); addLine("[\u5386\u53f2] \u5df2\u6e05\u7a7a") }
                        }) { Text("\u6e05\u7a7a") }
                        if (hasMicPermission().not()) {
                            OutlinedButton(onClick = {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text("\u8981\u9ea6\u6743") }
                        }
                    }
                }
            }
        }
    }
}