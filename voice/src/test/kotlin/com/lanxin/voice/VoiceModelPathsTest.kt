package com.lanxin.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceModelPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun resolveAsr_prefersLanXinLayout() {
        val base = tmp.root
        val model = File(base, "LanXin/asr/zipformer").apply { mkdirs() }
        File(model, "tokens.txt").writeText("a")
        val path = VoiceModelPaths.resolveAsrDir(base)
        assertNotNull(path)
        assertTrue(path!!.replace('\\', '/').endsWith("LanXin/asr/zipformer"))
    }

    @Test
    fun resolveAsr_fallsBackToLegacyModelsAsr() {
        val base = tmp.root
        val model = File(base, "models/asr/legacy-model").apply { mkdirs() }
        File(model, "tokens.txt").writeText("a")
        val path = VoiceModelPaths.resolveAsrDir(base)
        assertNotNull(path)
        assertTrue(path!!.replace('\\', '/').contains("models/asr/legacy-model"))
    }

    @Test
    fun resolveTts_lanXinLayout() {
        val base = tmp.root
        val model = File(base, "LanXin/tts/matcha").apply { mkdirs() }
        File(model, "tokens.txt").writeText("a")
        val path = VoiceModelPaths.resolveTtsDir(base)
        assertNotNull(path)
        assertTrue(path!!.replace('\\', '/').endsWith("LanXin/tts/matcha"))
    }

    @Test
    fun defaultDirs_pointUnderLanXin() {
        val base = tmp.root
        assertTrue(VoiceModelPaths.defaultAsrDir(base).replace('\\', '/').endsWith("LanXin/asr"))
        assertTrue(VoiceModelPaths.defaultTtsDir(base).replace('\\', '/').endsWith("LanXin/tts"))
    }

    @Test
    fun resolve_nullWhenMissing() {
        assertNull(VoiceModelPaths.resolveAsrDir(tmp.root))
        assertNull(VoiceModelPaths.resolveTtsDir(tmp.root))
    }

    @Test
    fun preferredName_selected() {
        val base = tmp.root
        File(base, "LanXin/asr/a").mkdirs()
        File(base, "LanXin/asr/b").apply {
            mkdirs()
            File(this, "tokens.txt").writeText("x")
        }
        val path = VoiceModelPaths.resolveAsrDir(base, preferredName = "b")
        assertNotNull(path)
        assertTrue(path!!.endsWith("b") || path.replace('\\', '/').endsWith("LanXin/asr/b"))
        assertEquals(
            File(base, "LanXin/asr/b").absolutePath,
            path
        )
    }
}
