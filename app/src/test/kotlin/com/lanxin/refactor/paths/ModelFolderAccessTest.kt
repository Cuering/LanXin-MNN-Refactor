package com.lanxin.refactor.paths

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelFolderAccessTest {

    @Test
    fun docIdToAbsolutePath_primary() {
        val path = ModelFolderAccess.docIdToAbsolutePath("primary:LanXin/models/local-llm")
        assertEquals("/storage/emulated/0/LanXin/models/local-llm", path)
    }

    @Test
    fun docIdToAbsolutePath_raw() {
        val path = ModelFolderAccess.docIdToAbsolutePath("raw:/storage/0000-ABCD/LanXin/models")
        assertEquals("/storage/0000-ABCD/LanXin/models", path)
    }

    @Test
    fun docIdToAbsolutePath_unknown_returnsNull() {
        assertNull(ModelFolderAccess.docIdToAbsolutePath("someRandomString"))
    }

    @Test
    fun looksLikeModelDir_llm_returnsTrue() {
        val dir = createTempDir("llm_test")
        File(dir, "config.json").writeText("{}")
        assertTrue(ModelFolderAccess.looksLikeModelDir(dir))
        dir.deleteRecursively()
    }

    @Test
    fun looksLikeModelDir_sherpaAsr_returnsTrue() {
        val dir = createTempDir("sherpa_asr")
        File(dir, "tokens.txt").writeText("<unk> 0")
        File(dir, "encoder.onnx").writeText("fake")
        assertTrue(ModelFolderAccess.looksLikeModelDir(dir))
        dir.deleteRecursively()
    }

    @Test
    fun looksLikeModelDir_empty_returnsFalse() {
        val dir = createTempDir("empty")
        assertFalse(ModelFolderAccess.looksLikeModelDir(dir))
        dir.deleteRecursively()
    }

    @Test
    fun looksLikeModelDir_live2d_returnsTrue() {
        val dir = createTempDir("live2d")
        File(dir, "Mao.model3.json").writeText("{}")
        assertTrue(ModelFolderAccess.looksLikeModelDir(dir))
        dir.deleteRecursively()
    }

    @Test
    fun listCandidateDirs_findsModelDirs() {
        val root = createTempDir("scan_test")
        // listCandidateDirs 只扫描 root 的直接子目录（含 root 自身）
        // 所以把模型目录直接放在 root 下
        val llmDir = File(root, "local-llm")
        llmDir.apply {
            mkdirs()
            File(this, "llm.mnn").writeText("fake")
        }
        val asrDir = File(root, "sherpa-onnx-paraformer")
        asrDir.apply {
            mkdirs()
            File(this, "tokens.txt").writeText("<unk> 0")
            File(this, "decoder.onnx").writeText("fake")
        }
        // 空目录不会被 looksLikeModelDir 检测到，且 listCandidateDirs 不会加入空目录
        File(root, "empty-dir").mkdirs()
        val live2dDir = File(root, "Mao")
        live2dDir.apply {
            mkdirs()
            File(this, "Mao.model3.json").writeText("{}")
        }
        val results = ModelFolderAccess.listCandidateDirs(root)
        System.err.println("Candidates: ${results.map { it.name }}")
        assertTrue("should find local-llm", results.any { it.name == "local-llm" })
        assertTrue("should find sherpa-onnx-paraformer", results.any { it.name == "sherpa-onnx-paraformer" })
        assertTrue("should find Mao", results.any { it.name == "Mao" })
        // empty-dir 无内容不应被加入
        assertFalse("should NOT find empty-dir", results.any { it.name == "empty-dir" })
        root.deleteRecursively()
    }

    @Test
    fun shortLabel_showsParentChild() {
        val label = ModelFolderAccess.shortLabel("/storage/emulated/0/LanXin/models/local-llm")
        assertTrue(label.contains("models") || label.contains("local-llm"))
    }

    @Test
    fun shortLabel_blank_returnsUnselected() {
        assertEquals("未选择", ModelFolderAccess.shortLabel(""))
    }
}
