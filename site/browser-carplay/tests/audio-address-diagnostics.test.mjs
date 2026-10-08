import test from 'node:test';
import assert from 'node:assert/strict';
import { iceAddressClass, localIceAddress } from '../audio-protocol.mjs';
import { audioEnvironment } from './audio-fixtures.mjs';

const rejected = [
  [undefined, 'unavailable'], [null, 'unavailable'], ['', 'unavailable'],
  ['0.0.0.0', 'unspecified'], ['::', 'unspecified'],
  ['100.64.0.1', 'shared-ipv4'], ['100.99.9.9', 'shared-ipv4'], ['100.127.255.254', 'shared-ipv4'],
  ['100.63.255.255', 'nonlocal-ipv4'], ['100.128.0.1', 'nonlocal-ipv4'], ['8.8.8.8', 'nonlocal-ipv4'],
  ['2001:db8::1', 'nonlocal-ipv6'], ['ff02::1', 'nonlocal-ipv6'],
  ['::ffff:100.99.9.9', 'shared-ipv4'], ['::ffff:8.8.8.8', 'nonlocal-ipv4'],
  ['fe80::1%wlan0', 'invalid-or-unsupported'], ['192.168.01.1', 'invalid-or-unsupported'],
  ['example.invalid', 'invalid-or-unsupported'], ['secret-token', 'invalid-or-unsupported'],
];

test('address diagnostics return fixed classes without relaxing local policy', () => {
  for (const [address, expected] of rejected) {
    assert.equal(iceAddressClass(address), expected);
    if (typeof address === 'string') assert.equal(localIceAddress(address), false);
  }
  for (const address of ['10.18.0.8', '192.168.31.166', '192.168.247.2', 'fd00::1', 'fe80::1', '::ffff:192.168.1.1', 'peer.local']) {
    assert.equal(iceAddressClass(address), 'allowed-local');
    assert.equal(localIceAddress(address), true);
  }
});

test('selected-pair rejection identifies the endpoint class while keeping raw addresses private', async () => {
  for (const side of ['local', 'remote']) for (const [address, expected] of rejected.filter(([value]) => typeof value === 'string')) {
    const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
    const peer = env.peers[0], original = peer.getStats.bind(peer); peer.packets = 7;
    peer.getStats = async () => { const stats = await original(); stats.get(side).address = address; return stats; };
    await env.tick();
    assert.equal(env.player.pending, false); assert.equal(peer.closed, true);
    assert.equal(env.signals.filter(signal => signal.type === 'audioReady').length, 0);
    const message = env.states.at(-1).message;
    assert.ok(message.includes(side === 'local' ? `browser=${expected}, Android=allowed-local` : `browser=allowed-local, Android=${expected}`));
    const output = JSON.stringify([env.player.getDiagnostics(), env.states]);
    if (address) assert.equal(output.includes(address), false);
    assert.doesNotMatch(output, /192\.168|candidate:|ice-pwd|secret-token/);
  }
});
