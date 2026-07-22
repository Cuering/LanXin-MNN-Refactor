# LanXin-MNN-Refactor

基于 **MNN 3.6.0** 的模块化本地陪伴 App（与旧 `LanXin-Android` 分离）。

## 模块

| 模块 | 职责 |
|------|------|
| `local-llm-core` | MNN so 下载 + JNI + `MnnBridge` |
| `local-llm-domain` | `LocalLlmEngine` / 状态机 / `ReplySanitizer` / `ChatRouter` / `CloudChatClient` |
| `core-memory` | 记忆存储 + prompt 注入 |
| `companion` | 陪伴会话编排（记忆 + 路由 + 可选语音） |
| `voice` | ASR / TTS / PetDisplay 接口与显式 Stub |
| `app` | 壳 UI |

## 进度概览

- P0–P5 完成：MNN、记忆、companion、路由、voice stub、记忆 UI。
- **P5.1**：OpenAI 兼容云端客户端 + DataStore 密钥设置 + 探测连通。
- 已修：`.kts` 禁止 `#` 注释；JUnit4 `@Test` 返回值须为 Unit。
- 下一步（可选）：sherpa native、Live2D WebView。

详见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

## 构建（GitHub Actions / 本地）

```bash
./gradlew :local-llm-core:downloadMnnNative
./gradlew :app:assembleDebug
./gradlew test
```

环境变量（可选）：

- `MNN_NATIVE_ZIP`：本地 zip 路径
- `MNN_NATIVE_URL`：自定义下载 URL

## 模型

将 MNN LLM 模型放到设备：

`Android/data/com.lanxin.refactor/files/models/local-llm/`

## P5 使用提示（App 内）

- 路由芯片：本地优先 / 仅本地 / 云优先
- 云端芯片：云关 / 云stub / 云真实（真实走 DataStore 中的 OpenAI 兼容配置）
- 「云设置」：Base URL / API Key / Model，可探测连通
- 「语音hint」：把输入框文本当 ASR 识别结果走 `chatFromVoice`
- 状态行会显示 ASR/TTS 的 `STUB` / `READY`，禁止静默失败
