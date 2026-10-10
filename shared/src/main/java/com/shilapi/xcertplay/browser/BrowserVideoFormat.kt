package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport

/** WebCodecs Annex-B configuration, derived from the actual avcC/hvcC record. */
internal data class BrowserVideoFormat(val codec: String, val parameterSets: ByteArray, val rtcFmtp: String = "") {
    companion object {
        fun parse(codec: VideoCodec, data: ByteArray): BrowserVideoFormat? {
            if (data.isEmpty() || data[0].toInt() != 1) return null
            return when (codec) {
                VideoCodec.H264 -> {
                    if (data.size < 7 || data[4].toInt() and 3 != 3) return null
                    val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
                    if (sps.isEmpty() || pps.isEmpty()) return null
                    BrowserVideoFormat("avc1." + data.sliceArray(1..3).joinToString("") { "%02X".format(it.toInt() and 255) },
                        byteArrayOf(0,0,0,1) + sps + byteArrayOf(0,0,0,1) + pps,
                        "packetization-mode=1;profile-level-id=" + data.sliceArray(1..3).joinToString("") { "%02x".format(it.toInt() and 255) } +
                            ";level-asymmetry-allowed=1")
                }
                VideoCodec.H265 -> {
                    if (data.size < 23 || data[21].toInt() and 3 != 3) return null
                    val sets = MediaCodecSupport.hevcCodecSpecificData(data)
                    if (sets.isEmpty()) return null
                    val profile = data[1].toInt() and 255
                    val space = arrayOf("", "A", "B", "C")[profile ushr 6]
                    var compatibility = 0
                    for (i in 2..5) compatibility = (compatibility shl 8) or (data[i].toInt() and 255)
                    val constraints = data.sliceArray(6..11).toMutableList()
                    while (constraints.isNotEmpty() && constraints.last() == 0.toByte()) constraints.removeAt(constraints.lastIndex)
                    val suffix = constraints.joinToString("") { ".%02X".format(it.toInt() and 255) }
                    BrowserVideoFormat("hev1.$space${profile and 31}.${Integer.toUnsignedString(Integer.reverse(compatibility), 16).uppercase()}.${if (profile and 32 == 0) "L" else "H"}${data[12].toInt() and 255}$suffix", sets,
                        "profile-space=${profile ushr 6};profile-id=${profile and 31};tier-flag=${(profile ushr 5) and 1};level-id=${data[12].toInt() and 255}")
                }
            }
        }
    }
}
