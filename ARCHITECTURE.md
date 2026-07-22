# LanXin-MNN-Refactor 架构

> 基于 MNN 3.6.0 的模块化本地陪伴 App。  
> 与旧 `LanXin-Android` 仓库分离；功能逐步迁移，优先 **记忆** 与 **本地陪伴**。

## 避坑清单（来自旧项目）

| 坑 | 对策 |
|---|---|
| `downloadMnnNative` 未执行 / jniLibs 缺失 | CI 强制跑任务并 `test -f` 校验 so；preBuild 依赖下载 |
| 只解压 arm64-v8a | 同时解压 `arm64-v8a` + `armeabi-v7a` |
| CMake 早于 so 就绪 | `configureCMake*` / `buildCMake*` / `externalNativeBuild*` 全部 dependsOn 下载 |
| so 加载失败静默 stub | 明确 `EngineState`（Ready / NativeMissing / LoadFailed / Stub）并在 UI 展示 |
| 元提示泄漏 | `ReplySanitizer` 在引擎出口统一清洗 |
| 巨型单体 app 模块 | 拆 `local-llm-core` / `local-llm-domain` / `core-memory` / `companion` / `voice` |
| ASR/TTS 伪装 READY | `VoiceEngineState` 与 LLM 同构；stub 永不伪装 Ready（除非显式 `simulateReady` 联调） |

## 模块划分

```
LanXin-MNN-Refactor/
├── app/                 # 壳：UI、DI 组装、权限
├── local-llm-core/      # JNI + MnnBridge（无业务）
├── local-llm-domain/    # LocalLlmEngine、Sanitizer、ChatRouter、CloudChatClient
├── core-memory/         # 记忆读写 / Decide / 注入预算（独立可替换）
├── companion/           # 全屏/桌宠陪伴编排（依赖 domain + memory + voice）
├── voice/               # ASR/TTS 接口 + Stub + sherpa 真引擎（AAR 构建期下载）
├── third_party/mnn/     # 头文件 + NOTICE（so 构建期下载，不进 git）
├── third_party/sherpa-onnx/  # NOTICE（AAR 不进 git）
└── .github/workflows/   # CI：MNN + sherpa AAR → test → assemble → 校验 so 进包
```

### 依赖方向（单向）

```
app → companion → local-llm-domain → local-llm-core
app → core-memory
companion → core-memory
companion → voice
app → voice
```

各模块可独立编译与替换；`local-llm-core` 仅暴露 JNI/加载能力，不含 UI。  
`voice` 不依赖 LLM，便于单独替换 sherpa 实现。

## 阶段

1. **P0** MNN 3.6.0 so + CMake + 薄 JNI + 加载状态可观测  
2. **P1** domain 引擎 + Sanitizer + 设置页  
3. **P2** core-memory 最小 CRUD + 注入到 prompt  
4. **P3** companion 本地对话闭环（记忆 enrich + 本地生成）  
5. **P4** 记忆 UI + JSON 导入导出  
6. **P5** 云端路由 / ASR / TTS / Live2D 骨架（接口 + stub + 路由策略；真 native 后续）

## 模型路径约定

设备外置（不进 git / 不进 APK）：

```
/sdcard/Android/data/<pkg>/files/models/local-llm/
  config.json
  llm.mnn
  llm.mnn.weight
  ...
```

`config.json` 中 `backend_type` 建议：`opencl`（失败再 `cpu`）。  
引擎层记录实际 backend 与 load 错误，禁止“看起来 READY 其实 stub”。

## P5 设计要点

### ChatRouter（local-llm-domain）

| 策略 | 行为 |
|------|------|
| `LOCAL_ONLY` | 仅本地，不可用则失败 |
| `PREFER_LOCAL` | 本地可用→本地；否则云端（若已配置） |
| `PREFER_CLOUD` | 云端优先，失败回落本地 |
| `CLOUD_ONLY` | 仅云端 |

`CloudChatClient` 接口可替换；默认 `UnconfiguredCloudChatClient`（`isConfigured=false`）。  
演示可用 `StubCloudChatClient`。真 HTTP 客户端放 app 或独立 `cloud` 模块，不塞进 domain 实现细节。

### voice 模块

- `AsrEngine` / `TtsEngine` + `VoiceEngineState`（与 LLM 同构可观测）
- `StubAsrEngine`：默认 `acceptHintAsResult` 支持无麦联调
- `StubTtsEngine`：可 speak 虚拟播报，状态默认 Stub；`simulateReady` 仅联调用
- **`sherpa/` 真引擎**（对齐旧 App Bridge，但**禁止假 READY**）：
  - `SherpaOnnxBridge` / `SherpaTtsBridge`：JNI，无 so 不抛
  - `SherpaAsrEngine` / `SherpaTtsEngine`：失败 → `NativeMissing` / `LoadFailed`
  - 无 so 时 `hintText` 可旁路联调，状态仍诚实
  - AAR：`:voice:downloadSherpaOnnxAar` → `voice/libs/*.aar`（gitignore）
  - 模型外置：`files/models/asr|tts/`（`VoiceModelPaths`）
- `PetDisplayState` + `PlaceholderPetDisplay`：Live2D 占位，不装 WebView/moc3

### companion 接线

`LocalCompanionSession` 增加：

- `routePolicy` / `cloudClient` / `asrEngine` / `ttsEngine` / `autoSpeak`
- `chat()` 走路由 + 可选 TTS
- `chatFromVoice(hintText|pcm)` → ASR → chat

## 进度

- [x] P0 仓库骨架 + MNN download(arm64/armv7) + JNI + CI 绿
- [x] P1 domain 状态机 + Sanitizer
- [x] P2 core-memory 文件持久化 + Decide + 轻量记取
- [x] P3 companion 本地闭环（enrich + generate）
- [x] 修：`app` 缺 serialization 依赖；根因是 `.kts` 误用 `#` 注释（应用 `//`）
- [x] 修：JUnit4 测试方法不能有返回值；`deleteRecursively()` 作最后表达式会返回 Boolean
- [x] P4 记忆 UI（列表/搜索/增删）+ `MemoryImportExport` JSON 导入导出（ignoreUnknownKeys 兼容旧字段）
- [x] P5 骨架：`ChatRouter` + `CloudChatClient` + `voice`(ASR/TTS/Pet stub) + companion 路由/语音轮次 + UI 策略切换 + 单测
- [x] P5.1 真云端：`OpenAiCompatibleCloudChatClient` + DataStore 设置页 + 探测连通 + 单测（HttpTransport 可注入）
- [x] P6 sherpa 真 ASR/TTS：AAR 构建期下载 + Bridge + Engine（失败不伪装 READY）+ UI 加载语音 + CI 校验 so
- [x] P7 真麦 `PcmAudioRecorder` + TTS `PcmAudioPlayer` 接 `SherpaTtsEngine` + Live2D WebView 壳（assets 占位）+ CI 校验 MNN/sherpa/live2d 全进 APK
- [x] P8 TTS 播报时长回调 + Live2D `lipSyncDuring` 嘴型占位动画 + UI 接入

### 与旧 App 语音差异（防闪退）

| 旧 App | 本仓库 |
|--------|--------|
| native 失败仍 `READY` + stub 文本 | `NativeMissing` / `LoadFailed`，`isUsable=false` |
| UI 可能当真机继续走 | 状态行展示真实 shortLabel；hint 旁路不改状态 |
| AAR 在 app 模块 | AAR 在 `voice` 以 `api` 传递，app 不重复打包 |

### 打包进 APK 的引擎（全量）

| 组件 | 来源 | APK 校验 |
|------|------|----------|
| MNN LLM | `local-llm-core` jniLibs + `libmnn_lanxin.so` | libMNN / libllm / libmnn_lanxin |
| Sherpa ASR/TTS | `voice` api AAR | libsherpa-onnx-jni |
| 录音/播放 | `PcmAudioRecorder` / `PcmAudioPlayer` | 纯 Java/Kotlin |
| Live2D 壳 | `Live2DWebViewHost` + assets | assets/live2d/index.html |

### 下次接续

1. 先读本文件 + `README.md`
2. 确认最新 CI 是否 success（含 MNN + sherpa + live2d 进 APK）
3. 可选加深：真 moc3 模型、流式 ASR、CloudConfig 热更新
4. 避坑：`*.gradle.kts` 禁止 `#` 注释；JUnit4 `@Test` 返回 Unit；**禁止 native 失败伪装 Ready**
