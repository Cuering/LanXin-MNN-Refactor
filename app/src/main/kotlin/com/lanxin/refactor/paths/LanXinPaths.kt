package com.lanxin.refactor.paths

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * LanXin 统一资源路径约定（与旧 LanXin-Android 对齐）。
 *
 * 用户可见根目录：`/sdcard/LanXin/`（公共存储优先，失败回退 app 私有外存）。
 *
 * 目录布局：
 * ```
 * LanXin/
 *   models/local-llm/light/   # MNN LLM (llm.mnn + llm.mnn.weight + config.json)
 *   asr/                       # sherpa ASR
 *   tts/                       # sherpa TTS
 *   live2d/                    # Live2D 模型
 *   backgrounds/               # 桌宠背景图
 *   music/                     # 背景音乐
 * ```
 *
 * 记忆 / 对话历史仍走 app 私有 `filesDir/memory/`（不用户可见）。
 */
object LanXinPaths {

    /** 用户可见的资源根目录名 */
    const val ROOT_DIR = "LanXin"

    /** 旧版目录名（兼容识别） */
    const val LEGACY_ROOT_DIR = "debug-assets"

    val STANDARD_SUBDIRS: List<String> = listOf(
        "models/local-llm",
        "models/local-llm/light",
        "asr",
        "tts",
        "live2d",
        "backgrounds",
        "music"
    )

    // ---- LLM ----

    const val LOCAL_LLM_REL = "$ROOT_DIR/models/local-llm/light"
    const val LOCAL_LLM_READY_FILE = "llm.mnn"

    fun localLlmDir(baseDir: File): File {
        val primary = File(baseDir, LOCAL_LLM_REL)
        if (isLocalLlmReady(primary)) return primary
        // fallback: 裸 models/local-llm under LanXin
        val alt = File(baseDir, "$ROOT_DIR/models/local-llm")
        if (isLocalLlmReady(alt)) return alt
        // fallback: 无 LanXin 前缀的旧 refactor 路径
        val legacy = File(baseDir, "models/local-llm/light")
        if (isLocalLlmReady(legacy)) return legacy
        val legacyBare = File(baseDir, "models/local-llm")
        if (isLocalLlmReady(legacyBare)) return legacyBare
        return primary
    }

    fun isLocalLlmReady(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val mnn = File(dir, LOCAL_LLM_READY_FILE)
        return mnn.isFile && mnn.length() > 0L
    }

    // ---- ASR ----

    fun asrDir(baseDir: File): File {
        val root = File(baseDir, "$ROOT_DIR/asr")
        root.listFiles()?.filter { it.isDirectory }?.forEach { kid ->
            if (File(kid, "tokens.txt").isFile) return kid
        }
        if (File(root, "tokens.txt").isFile) return root
        return root
    }

    // ---- TTS ----

    fun ttsDir(baseDir: File): File {
        val root = File(baseDir, "$ROOT_DIR/tts")
        val kids = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        if (kids.isNotEmpty()) return kids.first()
        return root
    }

    // ---- Live2D ----

    const val LIVE2D_MAO_REL = "$ROOT_DIR/live2d/Mao/Mao.model3.json"
    const val LIVE2D_MAO_ALT_REL = "$ROOT_DIR/live2d/mao/Mao.model3.json"

    fun live2dModelFile(baseDir: File): File {
        val primary = File(baseDir, LIVE2D_MAO_REL)
        if (primary.isFile) return primary
        val alt = File(baseDir, LIVE2D_MAO_ALT_REL)
        if (alt.isFile) return alt
        return primary
    }

    // ---- 目录解析（Context 入口） ----

    /**
     * baseDir 候选（优先级递减）：
     * 1. 公共存储根（其下挂 `LanXin/`）
     * 2. App 外存私有目录
     * 3. App 内存 filesDir
     */
    fun baseDirCandidates(context: Context): List<File> {
        val list = mutableListOf<File>()
        val publicStorage = Environment.getExternalStorageDirectory()
        if (publicStorage != null && publicStorage.isDirectory) {
            list.add(publicStorage)
        }
        context.getExternalFilesDir(null)?.let { list.add(it) }
        list.add(context.filesDir)
        return list.distinctBy { it.absolutePath }
    }

    /**
     * 解析资源 baseDir：
     * - 优先已有内容的 `/sdcard/LanXin`（与旧 App 共用）
     * - 其次 app 外存私有下的 `LanXin`
     * - 否则仍返回公共存储根（UI 展示推荐路径，用户可直接放）
     */
    fun resolveBaseDir(context: Context): File {
        val candidates = baseDirCandidates(context)
        for (base in candidates) {
            val lanXin = File(base, ROOT_DIR)
            if (lanXin.isDirectory && hasContent(lanXin)) {
                return base
            }
        }
        // 哪个 base 下已经有就绪 LLM，就用哪个
        for (base in candidates) {
            if (isLocalLlmReady(localLlmDir(base))) return base
        }
        return candidates.first()
    }

    /** 解析就绪 LLM 目录；没有就绪时返回推荐路径（公共 LanXin/light）。 */
    fun resolveLocalLlmPath(context: Context): File {
        for (base in baseDirCandidates(context)) {
            val dir = localLlmDir(base)
            if (isLocalLlmReady(dir)) return dir
        }
        return localLlmDir(resolveBaseDir(context))
    }

    /**
     * 确保目录结构存在（启动时调用）；对公共存储只尝试 mkdirs，
     * Android 11+ 无 MANAGE_EXTERNAL_STORAGE 时会静默失败。
     */
    fun ensureStructure(baseDir: File): List<String> {
        val created = mutableListOf<String>()
        for (rel in STANDARD_SUBDIRS) {
            val d = File(baseDir, "$ROOT_DIR/$rel")
            if (!d.exists() && d.mkdirs()) {
                created.add(rel)
            }
        }
        return created
    }

    fun hasContent(dir: File): Boolean {
        val kids = dir.listFiles() ?: return false
        return kids.isNotEmpty()
    }

    fun pathLooksLanXin(resolved: String): Boolean {
        if (resolved.isBlank()) return false
        return resolved.contains("/$ROOT_DIR/") ||
            resolved.contains("\\$ROOT_DIR\\") ||
            resolved.endsWith(ROOT_DIR) ||
            resolved.contains("/$LEGACY_ROOT_DIR/") ||
            resolved.endsWith(LEGACY_ROOT_DIR)
    }
}
