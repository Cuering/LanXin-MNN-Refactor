package com.lanxin.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioIoTest {

    @Test
    fun recorder_stubPcm_nonEmpty() = runBlocking {
        val rec = PcmAudioRecorder().also { it.forceStubHardware = true }
        val audio = rec.recordStubPcm(durationMs = 200, sampleRateHz = 16_000)
        assertTrue(audio.pcm16leMono.isNotEmpty())
        assertTrue(audio.isStub)
        assertEquals(16_000, audio.sampleRateHz)
        // 200ms * 16000 * 2 bytes ≈ 6400
        assertTrue(audio.byteCount in 6000..7000)
    }

    @Test
    fun recorder_startStop_stubPath() = runBlocking {
        val rec = PcmAudioRecorder().also {
            it.forceStubHardware = true
            it.audioRecordFactory = null
        }
        assertTrue(rec.startRecording().isSuccess)
        assertTrue(rec.isRecording)
        // 给一点「时长」
        Thread.sleep(80)
        val stopped = rec.stopRecording()
        assertTrue(stopped.isSuccess)
        val audio = stopped.getOrNull()!!
        assertTrue(audio.pcm16leMono.isNotEmpty())
        assertTrue(audio.isStub)
        assertFalse(rec.isRecording)
    }

    @Test
    fun recorder_doubleStart_fails() = runBlocking {
        val rec = PcmAudioRecorder().also { it.forceStubHardware = true }
        assertTrue(rec.startRecording().isSuccess)
        assertTrue(rec.startRecording().isFailure)
        rec.cancelRecording()
    }

    @Test
    fun player_forceStub_plays() = runBlocking {
        val player = PcmAudioPlayer().also { it.forceStub = true }
        val pcm = PcmAudioRecorder.synthesizeStubPcm(16_000, 100)
        val r = player.play(pcm, 16_000)
        assertTrue(r.isSuccess)
        val info = r.getOrNull()!!
        assertTrue(info.stub)
        assertEquals(pcm.size, info.bytes)
        assertTrue(info.durationMs in 90..110)
    }

    @Test
    fun player_empty_fails() = runBlocking {
        val player = PcmAudioPlayer().also { it.forceStub = true }
        assertTrue(player.play(ByteArray(0), 16_000).isFailure)
    }
}
