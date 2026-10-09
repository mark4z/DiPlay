"""Toolchain-free source guards for the narrowly ported native audio fixes.

Runtime coverage lives in AudioBufferProgressTest, AudioRebufferCapacityTest and
AudioCodecOutputReleaseTest. These guards do not replace the Android/JVM suite.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
MEDIA = ROOT / 'shared/src/main/java/com/shilapi/xcertplay/media'
SINK = (MEDIA / 'AndroidMediaSink.kt').read_text()
RENDERER = SINK.split('private class AudioRenderer(', 1)[1]
PROGRESS = (MEDIA / 'AudioBufferProgress.kt').read_text()


def between(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class NativeAudioContractTest(unittest.TestCase):
    def test_copied_pcm_releases_codec_output_before_blocking_write(self):
        drain = between(RENDERER, 'private fun drainCodec(', 'private fun writePcm(')
        self.assertIn('var copied = false', drain)
        self.assertIn('} finally {', drain)
        self.assertLess(drain.index('output.get(pcm, 0, size)'), drain.index('} finally {'))
        self.assertLess(drain.index('} finally {'), drain.index('codec.releaseOutputBuffer(index, false)'))
        self.assertLess(drain.index('codec.releaseOutputBuffer(index, false)'),
                        drain.index('if (copied) writePcm(pcm, 0, size)'))
        self.assertEqual(1, drain.count('writePcm('))

    def test_rebuffer_retains_pcm_and_restarts_the_tail_wait(self):
        maintenance = between(RENDERER, 'private fun maintainPlaybackBuffer()', 'private fun logStatsIfDue(')
        self.assertIn('startThresholdBytes / 2L', maintenance)
        self.assertIn('prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)', maintenance)
        self.assertIn('.coerceAtMost(startThresholdBytes.toLong()).toInt()', maintenance)
        self.assertLess(maintenance.index('track.pause()'),
                        maintenance.index('lastPcmWriteNs = System.nanoTime()'))
        self.assertLess(maintenance.index('lastPcmWriteNs = System.nanoTime()'),
                        maintenance.index('rebufferCount++'))
        self.assertNotIn('track.flush()', maintenance)

    def test_floor_does_not_remove_media_underrun_playing_or_queue_gates(self):
        self.assertIn('isMedia && playing && underrunSinceStart && compressedQueueEmpty &&', PROGRESS)
        self.assertIn('queuedBytes(rawHead) <= floorBytes', PROGRESS)
        start = between(RENDERER, 'private fun startPlayback(', 'private fun maintainPlaybackBuffer(')
        self.assertLess(start.index('underrunsAtPlaybackStart = track.underrunCount'), start.index('track.play()'))

    def test_relay_only_still_skips_audio_decode_and_microphone_workers(self):
        start = between(RENDERER, 'fun start()', 'fun submit(')
        self.assertLess(start.index('if (started || relayOnly) return'), start.index('thread.start()'))
        microphone = between(SINK, 'override fun onMicrophoneStarted(', 'override fun onMicrophoneStopped(')
        self.assertLess(microphone.index('if (relayOnly) return'), microphone.index('MicrophoneUplink('))
        for removed in ('onDecodedPcm', 'WebRtc', 'EchoReference', 'SoftwareOpusEncoder'):
            self.assertNotIn(removed, SINK)


if __name__ == '__main__':
    unittest.main()
