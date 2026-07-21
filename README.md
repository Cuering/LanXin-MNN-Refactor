# LanXin-MNN-Refactor

基于 **MNN 3.6.0** 的模块化本地陪伴 App（与旧 `LanXin-Android` 分离）。

## 模块

| 模块 | 职责 |
|------|------|
| `local-llm-core` | MNN so 下载 + JNI + `MnnBridge` |
| `local-llm-domain` | `LocalLlmEngine` / 状态机 / `ReplySanitizer` |
| `core-memory` | 记忆存储 + prompt 注入 |
| `companion` | 本地陪伴会话编排 |
| `app` | 壳 UI |

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

需含 `config.json` + `llm.mnn` 等。建议 `backend_type` 优先 `opencl`，失败再 `cpu`。

## 避坑

- CI 校验 arm64 + armv7 so 均存在
- 引擎状态可观测（NativeMissing / LoadFailed / Ready）
- 回复统一 `ReplySanitizer` 清洗
