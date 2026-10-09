"""Source guards for ownership boundaries; behavior is in BrowserLanTakeoverTest.

These run without an Android/Gradle toolchain. They deliberately do not model the
transport in another language or claim to replace its loopback JVM/TLS tests.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
BROWSER = ROOT / 'shared/src/main/java/com/shilapi/xcertplay/browser'
SERVER = (BROWSER / 'BrowserLanServer.kt').read_text()
OUTPUT = (BROWSER / 'BrowserOutput.kt').read_text()


def between(source, start, end):
    return source.split(start, 1)[1].split(end, 1)[0]


class BrowserTakeoverContractTest(unittest.TestCase):
    def test_candidate_registration_follows_origin_and_strict_approval_validation(self):
        reader = between(SERVER, 'private fun readLoop()', '/** Only the reader commits')
        self.assertLess(reader.index('BrowserLanProtocol.classifyRequest('),
                        reader.index('BrowserLanProtocol.requestsApproval('))
        self.assertLess(reader.index('BrowserLanProtocol.requestsApproval('),
                        reader.index('claimViewer(this)'))
        claim = between(SERVER, 'claimViewer = { next', 'connections.add(session)')
        self.assertNotRegex(claim, r'\bowner\s*=')
        self.assertIn('next.sequence <= newestCandidateSequence', claim)

    def test_transfer_and_callbacks_share_the_application_lock(self):
        self.assertIn('viewerAssets = assets, lock = lock)', OUTPUT)
        self.assertIn('private val lock: Any = Any()', SERVER)
        self.assertIn('onAuthenticated = { synchronized(lock)', SERVER)
        text = between(SERVER, 'onText = { text', 'onDisconnected =')
        disconnected = between(SERVER, 'onDisconnected = { synchronized(lock)', 'onFinished =')
        self.assertIn('synchronized(lock)', text)
        self.assertIn('owner === session', text)
        self.assertIn('owner === session', disconnected)
        self.assertLess(disconnected.index('owner === session'), disconnected.index('onDisconnected()'))
        self.assertEqual(2, SERVER.count('return synchronized(lock) { owner?.send('))

    def test_retiring_sockets_stay_in_the_bounded_deadline_set(self):
        self.assertIn('(connections.size - if (owner != null) 1 else 0)', SERVER)
        self.assertEqual(2, SERVER.count('sessions = connections.toList()') +
                         SERVER.count('sessions = synchronized(lock) { connections.toList() }'))
        self.assertEqual(1, SERVER.count('connections.remove('))
        self.assertIn('connections.remove(finished)', between(SERVER, 'onFinished = { finished', 'diagnostic ='))
        self.assertIn('if (now - supersededAt >= 1_000) stop()', SERVER)

    def test_superseded_marker_is_exact_and_never_blocks_the_transfer_lock(self):
        marker = re.search(r'SUPERSEDED_CLOSE = byteArrayOf\((0x[0-9a-f]+), (0x[0-9a-f]+)\.toByte\(\)\) \+ "([^"]+)"', SERVER)
        self.assertIsNotNone(marker)
        high, low, reason = marker.groups()
        self.assertEqual((int(high, 16) << 8) | int(low, 16), 4001)
        self.assertEqual(reason, 'superseded')
        supersede = between(SERVER, 'fun supersede()', 'fun start()')
        self.assertIn('approval?.invalidate()', supersede)
        self.assertIn('outbound.finishWithClose(SUPERSEDED_CLOSE)', supersede)
        self.assertNotIn('join(', supersede)
        self.assertNotIn('socket.close(', supersede)
        self.assertLess(supersede.index('supersededAt ='), supersede.index('superseded.set(true)'))

    def test_new_viewer_revokes_old_touch_before_announcing_readiness(self):
        authenticated = between(OUTPUT, 'private fun viewerAuthenticated(', 'fun stop()')
        self.assertLess(authenticated.index('++viewerGeneration'), authenticated.index('releaseTouches()'))
        self.assertLess(authenticated.index('releaseTouches()'), authenticated.index('viewerConnected = true'))
        self.assertIn('lastOwnershipRequestId = 0L', authenticated)
        self.assertIn('waitingForKey = true', authenticated)
        self.assertIn('sendConfig()', authenticated)
        self.assertIn('requestKeyframe()', authenticated)

    def test_new_prompt_dismisses_exact_previous_request(self):
        approval = between(OUTPUT, 'private fun requestApproval(', 'private fun finishApproval(')
        self.assertIn('previous = pendingApproval', approval)
        self.assertIn('pendingApproval = request', approval)
        self.assertLess(approval.index('it.reject()'), approval.index('ui.requested(request)'))
        self.assertLess(approval.index('ui?.finished?.invoke(it.id)'), approval.index('ui.requested(request)'))


if __name__ == '__main__':
    unittest.main()
