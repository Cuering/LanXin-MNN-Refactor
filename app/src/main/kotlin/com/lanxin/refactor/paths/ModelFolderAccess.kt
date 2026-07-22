package com.lanxin.refactor.paths

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * \u6a21\u578b\u76ee\u5f55\u9009\u62e9\uff1aSAF \u6811 URI \u2192 \u771f\u673a\u8def\u5f84\uff08native \u5f15\u64ce\u9700\u8981\u7edd\u5bf9\u8def\u5f84\uff09\u3002
 *
 * \u4ec5\u53ef\u9760\u89e3\u6790 `primary:`\uff08\u5185\u7f6e\u5b58\u50a8\uff09\u3002\u5916\u7f6e SD \u5361\u53ef\u80fd\u8fd4\u56de null\uff0c\u8c03\u7528\u65b9\u5e94\u63d0\u793a\u3002
 */
object ModelFolderAccess {

    fun takePersistableRead(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // \u90e8\u5206\u673a\u578b/\u6587\u6863\u63d0\u4f9b\u65b9\u4e0d\u652f\u6301 persistable
        }
    }

    /**
     * @return \u53ef\u7ed9 MNN/sherpa \u4f7f\u7528\u7684\u7edd\u5bf9\u8def\u5f84\uff0c\u5931\u8d25 null
     */
    fun treeUriToAbsolutePath(uri: Uri): String? {
        val docId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        return docIdToAbsolutePath(docId)
    }

    fun docIdToAbsolutePath(docId: String): String? {
        // primary:LanXin/asr/foo  \u2192 /storage/emulated/0/LanXin/asr/foo
        if (docId.startsWith("primary:", ignoreCase = true)) {
            val rel = docId.substringAfter(':')
            return File("/storage/emulated/0", rel).absolutePath
        }
        // raw:/storage/emulated/0/...
        if (docId.startsWith("raw:", ignoreCase = true)) {
            return docId.removePrefix("raw:").removePrefix("RAW:")
        }
        return null
    }

    /** \u5728 [root] \u4e0b\u626b\u63cf\u4e00\u5c42\u5b50\u76ee\u5f55\uff08\u542b\u81ea\u8eab\u82e5\u50cf\u6a21\u578b\u76ee\u5f55\uff09\u3002 */
    fun listCandidateDirs(root: File, max: Int = 24): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<File>()
        if (looksLikeModelDir(root)) out.add(root)
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { child ->
                if (out.size >= max) return@forEach
                if (looksLikeModelDir(child) || child.listFiles()?.isNotEmpty() == true) {
                    out.add(child)
                }
            }
        return out.distinctBy { it.absolutePath }.take(max)
    }

    fun looksLikeModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val names = dir.list()?.map { it.lowercase() }?.toSet().orEmpty()
        if (names.isEmpty()) return false
        // LLM
        if (names.any { it == "config.json" || it.endsWith(".mnn") || it.contains("llm") }) return true
        // sherpa ASR/TTS \u5e38\u89c1
        if (names.any {
                it.endsWith(".onnx") || it.endsWith(".ort") ||
                    it.contains("tokens") || it.contains("vocab") ||
                    it.contains("encoder") || it.contains("decoder") ||
                    it.contains("joiner") || it.contains("vocos") ||
                    it.contains("matcha") || it.contains("vits")
            }
        ) {
            return true
        }
        // Live2D
        if (names.any { it.endsWith(".model3.json") || it.endsWith(".moc3") }) return true
        return false
    }

    fun shortLabel(path: String): String {
        if (path.isBlank()) return "\u672a\u9009\u62e9"
        val f = File(path)
        val parent = f.parentFile?.name ?: ""
        return if (parent.isNotBlank()) "$parent/${f.name}" else f.name
    }
}
