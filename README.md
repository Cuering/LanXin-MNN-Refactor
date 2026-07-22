# LanXin-MNN-Refactor

基于 **MNN 3.6.0** 的模块化本地陪伴 App（与旧 `LanXin-Android` 分离）。

## 模块

| 模块 | 职责 |
|------|------|
| `local-llm-core` | MNN so 下载 + JNI + `MnnBridge` |
| `local-llm-domain` | `LocalLlmEngine` / 状态机 / `ReplySanitizer` / `ChatRouter` / `CloudChatClient` |
| `core-memory` | 记忆存储 + prompt 注入 |
| `companion` | 陪伴会话编排（记忆 + 路由 + 可选语音） |
| `voice` | ASR/TTS 接口、Stub、**sherpa 真引擎**（AAR 构建期下载） |
| `app` | 壳 UI |

## 进度概览

- P0–P5：MNN、记忆、companion、路由、voice stub、记忆 UI
- P5.1：OpenAI 兼容云端 + DataStore 设置
- **P6：sherpa-onnx 真 ASR/TTS**（失败不伪装 READY，避免旧 App 假就绪闪退路径）

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

```
Android/data/com.lanxin.refactor/files/
  models/local-llm/     # MNN LLM
  models/asr/<name>/    # sherpa ASR（zipformer 流式或 paraformer）
  models/tts/<name>/    # sherpa TTS（matcha/vits，matcha 需 vocoder）
```

ASR 示例（流式中文小模型）：`scripts` 可参考旧仓 `download-debug-asr.sh`  
默认约定见 `VoiceModelPaths`。

## App 内使用

- **加载语音**：按外置路径 load ASR/TTS；状态行显示 `STUB` / `NATIVE_MISSING` / `READY(sherpa:…)`
- **语音hint**：输入框文本当识别结果（无麦 / 无 so 可联调）
- 路由：本地优先 / 仅本地 / 云优先；云设置页配 OpenAI 兼容 API

## 与旧语音模块

旧 App 在 native 失败时仍标 READY，会话/UI 易走错路径甚至闪退。  
本仓库：**失败状态诚实**；联调只用 hint 旁路，不把 stub 当真机。
