package com.lanxin.voice

/**
 * 一段 16-bit LE 单声道 PCM。
 * [isStub] = true 表示未触碰硬件（单测 / 无权限旁路）。
 */
data class RecordedAudio(
    val pcm16leMono: ByteArray,
    val sampleRateHz: Int,
    val durationMs: Long,
    val isStub: Boolean = false
) {
    val byteCount: Int get() = pcm16leMono.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedAudio) return false
        return sampleRateHz == other.sampleRateHz &&
            durationMs == other.durationMs &&
            isStub == other.isStub &&
            pcm16leMono.contentEquals(other.pcm16leMono)
    }

    override fun hashCode(): Int {
        var r = sampleRateHz
        r = 31 * r + durationMs.hashCode()
        r = 31 * r + isStub.hashCode()
        r = 31 * r + pcm16leMono.contentHashCode()
        return r
    }
}
