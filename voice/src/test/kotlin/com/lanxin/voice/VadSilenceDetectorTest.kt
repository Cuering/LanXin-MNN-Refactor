package com.lanxin.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VadSilenceDetectorTest {

    private fun sineFrame(
        samples: Int,
        amplitude: Float,
        freq: Double = 220.0,
        sampleRate: Int = 16_000
    ): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            val s = (sin(2.0 * Math.PI * freq * t) * amplitude * 32767.0).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun silenceFrame(samples: Int): ByteArray = ByteArray(samples * 2)

    @Test
    fun speechThenSilence_autoStops() {
        val vad = VadSilenceDetector(
            sampleRateHz = 16_000,
            frameMs = 20,
            speechThreshold = 0.02f,
            silenceMs = 200L,
            minSpeechMs = 80L,
            maxDurationMs = 5_000L,
            prerollMs = 0L
        )
        val spf = vad.samplesPerFrame
        // 120ms speech
        repeat(6) {
            vad.feedPcm16leFrame(sineFrame(spf, 0.4f))
        }
        assertEquals(VadSilenceDetector.Phase.IN_SPEECH, vad.currentPhase)
        // 240ms silence → stop
        var stopped = false
        repeat(15) {
            vad.feedPcm16leFrame(silenceFrame(spf))
            if (vad.shouldStop) {
                stopped = true
                return@repeat
            }
        }
        assertTrue(stopped)
        assertTrue(vad.speechMs >= 80L)
    }

    @Test
    fun pureSilence_waitsUntilMaxDuration() {
        val vad = VadSilenceDetector(
            sampleRateHz = 16_000,
            frameMs = 20,
            silenceMs = 200L,
            minSpeechMs = 80L,
            maxDurationMs = 200L,
            prerollMs = 0L
        )
        val spf = vad.samplesPerFrame
        repeat(20) {
            vad.feedPcm16leFrame(silenceFrame(spf))
        }
        assertTrue(vad.shouldStop)
        assertEquals(VadSilenceDetector.Phase.SHOULD_STOP, vad.currentPhase)
    }

    @Test
    fun feedPcmStream_processesChunks() {
        val vad = VadSilenceDetector(
            sampleRateHz = 16_000,
            frameMs = 20,
            silenceMs = 100L,
            minSpeechMs = 40L,
            maxDurationMs = 3_000L,
            prerollMs = 0L
        )
        val spf = vad.samplesPerFrame
        val speech = ByteArray(0).let {
            var acc = ByteArray(0)
            repeat(8) { acc += sineFrame(spf, 0.5f) }
            acc
        }
        assertFalse(vad.feedPcmStream(speech))
        assertEquals(VadSilenceDetector.Phase.IN_SPEECH, vad.currentPhase)
        val silence = ByteArray(0).let {
            var acc = ByteArray(0)
            repeat(10) { acc += silenceFrame(spf) }
            acc
        }
        assertTrue(vad.feedPcmStream(silence))
    }

    @Test
    fun reset_clearsState() {
        val vad = VadSilenceDetector(prerollMs = 0L, minSpeechMs = 20L, silenceMs = 40L)
        vad.feedPcm16leFrame(sineFrame(vad.samplesPerFrame, 0.5f))
        vad.reset()
        assertEquals(VadSilenceDetector.Phase.WAITING_SPEECH, vad.currentPhase)
        assertEquals(0L, vad.elapsedMs)
    }

    @Test
    fun snapshot_shortLabel() {
        val vad = VadSilenceDetector(prerollMs = 0L)
        vad.feedPcm16leFrame(sineFrame(vad.samplesPerFrame, 0.3f))
        val s = vad.snapshot()
        assertTrue(s.shortLabel.contains("vad="))
        assertTrue(s.shortLabel.contains("rms="))
    }
}

class VadAutoStopRecorderTest {

    @Test
    fun stubMode_autoStopsAndReturnsPcm() = runBlocking {
        val mic = PcmAudioRecorder().also { it.forceStubHardware = true }
        val auto = VadAutoStopRecorder(mic) { rate ->
            VadSilenceDetector(
                sampleRateHz = rate,
                frameMs = 20,
                silenceMs = 100L,
                minSpeechMs = 50L,
                maxDurationMs = 3_000L,
                prerollMs = 0L
            )
        }
        assertTrue(auto.start().isSuccess)
        assertTrue(auto.isRecording)
        val result = auto.awaitAutoStop(pollMs = 20L, timeoutMs = 5_000L)
        assertTrue(result.isSuccess)
        val audio = result.getOrNull()!!
        assertTrue(audio.pcm16leMono.isNotEmpty())
        assertTrue(audio.isStub)
        assertFalse(auto.isRecording)
        val snap = auto.lastSnapshot
        assertTrue(snap != null)
        assertEquals(VadSilenceDetector.Phase.SHOULD_STOP, snap!!.phase)
    }

    @Test
    fun cancel_whileWaiting() = runBlocking {
        val mic = PcmAudioRecorder().also { it.forceStubHardware = true }
        val auto = VadAutoStopRecorder(mic)
        assertTrue(auto.start().isSuccess)
        auto.cancel()
        assertFalse(auto.isRecording)
    }
}
