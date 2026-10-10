package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class BrowserVideoFormatTest {
    @Test fun avcDerivesActualProfileAndParameterSets() {
        val data = byteArrayOf(1, 100, 0, 42, -1, -31, 0, 4, 103, 100, 0, 42, 1, 0, 2, 104, 1)
        val result = BrowserVideoFormat.parse(VideoCodec.H264, data)!!
        assertEquals("avc1.64002A", result.codec)
        assertEquals("packetization-mode=1;profile-level-id=64002a;level-asymmetry-allowed=1", result.rtcFmtp)
        assertArrayEquals(byteArrayOf(0,0,0,1,103,100,0,42,0,0,0,1,104,1), result.parameterSets)
    }
    @Test fun rejectsShortAndUnsupportedNalLengthRecords() {
        assertNull(BrowserVideoFormat.parse(VideoCodec.H264, byteArrayOf(1,2)))
        assertNull(BrowserVideoFormat.parse(VideoCodec.H265, ByteArray(23)))
        assertNull(BrowserVideoFormat.parse(VideoCodec.H264, byteArrayOf(1,100,0,42,0,0,0)))
    }
    @Test fun hevcDerivesProfileCompatibilityTierLevelAndConstraints() {
        val data = ByteArray(23)
        data[0] = 1; data[1] = 1; data[5] = 6; data[6] = -80; data[12] = 120
        data[21] = 3; data[22] = 1
        val record = data + byteArrayOf(32,0,1,0,2,64,1)
        val result = BrowserVideoFormat.parse(VideoCodec.H265, record)!!
        assertEquals("hev1.1.60000000.L120.B0", result.codec)
        assertEquals("profile-space=0;profile-id=1;tier-flag=0;level-id=120", result.rtcFmtp)
        assertArrayEquals(byteArrayOf(0,0,0,1,64,1), result.parameterSets)
    }
}
