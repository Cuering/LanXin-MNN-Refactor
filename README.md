# LanXin-MNN-Refactor

基于 **MNN 3.6.0** 的模块化本地陪伴 App（与旧 `LanXin-Android` 分离）。

## 模块

| 模块 | 职责 |
|------|------|
| `local-llm-core` | MNN so 下载 + JNI + `MnnBridge`（`libmnn_lanxin`） |
| `local-llm-domain` | `LocalLlmEngine` / 状态机 / `ReplySanitizer` / `ChatRouter` / `CloudChatClient` |
| `core-memory` | 记忆存储 + prompt 注入 |
| `companion` | 陪伴会话编排（记忆 + 路由 + 可选语音） |
| `voice` | ASR/TTS 接口、Stub、**sherpa 真引擎**、录音/播放、Live2D 壳 |
| `app` | 壳 UI + assets/live2d 占位 |

## 打进包的引擎（全量）

| 引擎 | 打包方式 | 无模型时 |
|------|----------|----------|
| MNN LLM | jniLibs + CMake `libmnn_lanxin.so` | `NativeMissing` / `LoadFailed`，不伪装 READY |
| Sherpa ASR | `voice` 依赖 sherpa-onnx AAR（api） | 同上 |
| Sherpa TTS | 同上 + `PcmAudioPlayer` 播 PCM | 同上 |
| 麦克风 | `PcmAudioRecorder`（需 RECORD_AUDIO） | 可 stub / 语音hint |
| Live2D | WebView + `assets/live2d/index.html` | 占位 canvas，可换 moc3 |

## 进度概览

- P0–P5：MNN、记忆、companion、路由、voice stub、记忆 UI
- P5.1：OpenAI 兼容云端 + DataStore 设置
- P6：sherpa-onnx 真 ASR/TTS
- **P7：真麦录音 + TTS 播放 + Live2D 壳；CI 校验 MNN/sherpa/live2d 全进 APK**

详见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

## 构建

```bash
./gradlew :local-llm-core:downloadMnnNative
./gradlew :voice:downloadSherpaOnnxAar
./gradlew :app:assembleDebug
./gradlew test
```

环境变量（可选）：

| 变量 | 含义 |
|------|------|
| `MNN_NATIVE_ZIP` / `MNN_NATIVE_URL` | MNN 预编译 zip |
| `SHERPA_ONNX_AAR` / `SHERPA_ONNX_AAR_URL` | sherpa Android AAR |

## 设备模型路径

与旧 App 一致，优先用户可见目录：

```
/sdcard/LanXin/
  models/local-llm/light/   # MNN LLM（config.json + llm.mnn + llm.mnn.weight）
  asr/<name>/               # sherpa ASR（zipformer 流式或 paraformer）
  tts/<name>/               # sherpa TTS（matcha/vits，matcha 需 vocoder）
  live2d/                   # Live2D
  backgrounds/  music/
```

公共存储不可写时回退：

```
Android/data/com.lanxin.refactor/files/LanXin/   # 同上结构
```

仍兼容旧 `files/models/local-llm|asr|tts`。解析见 `LanXinPaths` / `VoiceModelPaths`。

### 放模型示例（adb）

```bash
# 公共目录（推荐，与旧 App 同路径可共用）
adb shell mkdir -p /sdcard/LanXin/models/local-llm/light
adb push config.json llm.mnn llm.mnn.weight /sdcard/LanXin/models/local-llm/light/
```

## App 内使用

- **加载本地脑 / 加载语音**：外置路径 load；状态行显示真实 `STUB` / `NATIVE_MISSING` / `READY(...)`
- **要麦权 → 开始录音 → 停麦发送**：真 PCM → ASR → chat → TTS 播放
- **语音hint**：输入框文本当识别结果（无麦 / 无 so 可联调）
- **Live2D 区**：顶部 WebView 占位；说话时驱动 mouth/expression 占位 API
- 路由：本地优先 / 仅本地 / 云优先；云设置页配 OpenAI 兼容 API

## 与旧语音模块

旧 App 在 native 失败时仍标 READY，会话/UI 易走错路径甚至闪退。  
本仓库：**失败状态诚实**；联调只用 hint 旁路，不把 stub 当真机。
