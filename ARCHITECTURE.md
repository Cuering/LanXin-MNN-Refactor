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
| 巨型单体 app 模块 | 拆 `local-llm-core` / `local-llm-domain` / `core-memory` / `companion` |

## 模块划分

```
LanXin-MNN-Refactor/
├── app/                 # 壳：UI、DI 组装、权限
├── local-llm-core/      # JNI + MnnBridge（无业务）
├── local-llm-domain/    # LocalLlmEngine、配置、Sanitizer、路由接口
├── core-memory/         # 记忆读写 / Decide / 注入预算（独立可替换）
├── companion/           # 全屏/桌宠陪伴编排（依赖 domain + memory）
├── third_party/mnn/     # 头文件 + NOTICE（so 构建期下载，不进 git）
└── .github/workflows/   # CI：下载 MNN → 编译 → 单测
```

### 依赖方向（单向）

```
app → companion → local-llm-domain → local-llm-core
app → core-memory
companion → core-memory
```

各模块可独立编译与替换；`local-llm-core` 仅暴露 JNI/加载能力，不含 UI。

## 阶段

1. **P0** MNN 3.6.0 so + CMake + 薄 JNI + 加载状态可观测  
2. **P1** domain 引擎 + Sanitizer + 设置页  
3. **P2** core-memory 最小 CRUD + 注入到 prompt  
4. **P3** companion 本地对话闭环（记忆 enrich + 本地生成）  
5. **P4+** 从旧 App 迁移 ASR/TTS/Live2D/云端路由（可选）

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
