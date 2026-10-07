package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.DecodedAudioFormat
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BrowserAudioPacketTest {
    @Test fun envelopePreservesMetadataAndLittleEndianSignedStereo() {
        val pcm = byteArrayOf(0, 0x40, 0, 0xe0.toByte(), 0, 0x80.toByte(), 0xff.toByte(), 0x7f)
        val encoded = BrowserAudioPacket.encode(517, 0x10203, DecodedAudioFormat(48_000, 2),
            0x1_0000_0001, pcm, 0, pcm.size, 0.2f)
        val header = ByteBuffer.wrap(encoded)
        assertEquals(3, header.get().toInt()); assertEquals(1, header.get().toInt())
        assertEquals(1, header.get().toInt()); assertEquals(2, header.get().toInt())
        assertEquals(517, header.int); assertEquals(0x10203, header.int)
        assertEquals(48_000, header.int); assertEquals(0x1_0000_0001, header.long)
        assertEquals(2, header.int); assertEquals(0.2f, header.float, 0f); assertEquals(0, header.int)
        assertArrayEquals(pcm, encoded.copyOfRange(36, encoded.size))
        header.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16384, header.short.toInt()); assertEquals(-8192, header.short.toInt())
        assertEquals(-32768, header.short.toInt()); assertEquals(32767, header.short.toInt())
    }

    @Test fun unsupportedFormatsAndOversizedOrUnalignedPcmAreRejected() {
        val stereo = DecodedAudioFormat(48_000, 2)
        assertFalse(DecodedAudioFormat(48_000, 2, 4).supported)
        assertFalse(DecodedAudioFormat(48_000, 3).supported)
        assertFalse(DecodedAudioFormat(0, 1).supported)
        assertEquals(16420, BrowserAudioPacket.encode(1, 1, stereo, 0, ByteArray(16384), 0, 16384, 1f).size)
        for ((format, size) in listOf(stereo to 16388, stereo to 3, DecodedAudioFormat(48_000, 2, 4) to 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                BrowserAudioPacket.encode(1, 1, format, 0, ByteArray(size), 0, size, 1f)
            }
        }
    }
}
