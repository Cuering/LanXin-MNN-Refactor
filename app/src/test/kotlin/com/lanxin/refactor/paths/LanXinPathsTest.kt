package com.lanxin.refactor.paths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LanXinPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun localLlmDir_prefersLightWhenReady() {
        val base = tmp.root
        val light = File(base, "LanXin/models/local-llm/light").apply { mkdirs() }
        File(light, "llm.mnn").writeText("fake")
        val resolved = LanXinPaths.localLlmDir(base)
        assertEquals(light.canonicalFile, resolved.canonicalFile)
        assertTrue(LanXinPaths.isLocalLlmReady(resolved))
    }

    @Test
    fun localLlmDir_fallsBackToBareLocalLlm() {
        val base = tmp.root
        val bare = File(base, "LanXin/models/local-llm").apply { mkdirs() }
        File(bare, "llm.mnn").writeText("fake")
        val resolved = LanXinPaths.localLlmDir(base)
        assertEquals(bare.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun localLlmDir_legacyWithoutLanXinPrefix() {
        val base = tmp.root
        val legacy = File(base, "models/local-llm").apply { mkdirs() }
        File(legacy, "llm.mnn").writeText("fake")
        val resolved = LanXinPaths.localLlmDir(base)
        assertEquals(legacy.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun localLlmDir_defaultWhenEmpty() {
        val base = tmp.root
        val resolved = LanXinPaths.localLlmDir(base)
        assertTrue(resolved.path.replace('\\', '/').endsWith("LanXin/models/local-llm/light"))
        assertFalse(LanXinPaths.isLocalLlmReady(resolved))
    }

    @Test
    fun asrDir_picksChildWithTokens() {
        val base = tmp.root
        val asrRoot = File(base, "LanXin/asr").apply { mkdirs() }
        File(asrRoot, "empty-model").mkdirs()
        val good = File(asrRoot, "zipformer-zh").apply { mkdirs() }
        File(good, "tokens.txt").writeText("a")
        val resolved = LanXinPaths.asrDir(base)
        assertEquals(good.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun ensureStructure_createsStandardSubdirs() {
        val base = tmp.root
        val created = LanXinPaths.ensureStructure(base)
        assertTrue(created.isNotEmpty())
        for (rel in LanXinPaths.STANDARD_SUBDIRS) {
            assertTrue(File(base, "LanXin/$rel").isDirectory)
        }
        // 幂等
        val again = LanXinPaths.ensureStructure(base)
        assertTrue(again.isEmpty())
    }

    @Test
    fun pathLooksLanXin() {
        assertTrue(LanXinPaths.pathLooksLanXin("/sdcard/LanXin/models/local-llm/light"))
        assertTrue(LanXinPaths.pathLooksLanXin("C:\\Users\\x\\LanXin\\asr"))
        assertFalse(LanXinPaths.pathLooksLanXin("/tmp/other"))
    }

    @Test
    fun live2dModelFile_prefersMao() {
        val base = tmp.root
        val mao = File(base, "LanXin/live2d/Mao/Mao.model3.json").apply {
            parentFile!!.mkdirs()
            writeText("{}")
        }
        assertEquals(mao.canonicalFile, LanXinPaths.live2dModelFile(base).canonicalFile)
    }
}
