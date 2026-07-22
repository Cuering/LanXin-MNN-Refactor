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

## 进度概览

- 已完成核心模块 (`local-llm-core`, `local-llm-domain`, `core-memory`, `companion`) 的骨架搭建与 MNN 集成。
- `core-memory` 已实现文件持久化、记忆决策门 (`MemoryDecide`) 和轻量自动记取。
- 当前 `app` 模块因缺少 `kotlinx.serialization.json` 依赖，编译失败。**正在修复。**


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
