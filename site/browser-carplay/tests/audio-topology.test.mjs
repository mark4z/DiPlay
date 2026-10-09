import test from 'node:test';
import assert from 'node:assert/strict';
import { audioEnvironment } from './audio-fixtures.mjs';

const changes = [
  stats => { stats.get('remote').address = '100.99.0.1'; },
  stats => { stats.get('local').address = '8.8.8.8'; },
  stats => { stats.get('remote').address = '2001:db8::1'; },
  stats => { stats.get('local').address = ''; delete stats.get('remote').address; },
  stats => { stats.get('remote').candidateType = 'prflx'; },
  stats => { stats.get('local').candidateType = 'relay'; },
  stats => { stats.get('local').candidateType = 'srflx'; },
  stats => { delete stats.get('local').protocol; },
  stats => { stats.get('pair').state = 'in-progress'; },
  stats => { stats.delete('remote'); stats.delete('local'); },
  stats => { for (const key of ['transport', 'pair', 'local', 'remote']) stats.delete(key); },
];

test('ICE topology and missing or redacted pair metadata do not block readiness or liveness', async () => {
  for (const change of changes) {
    const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
    const peer = env.peers[0], original = peer.getStats.bind(peer);
    peer.getStats = async () => { const stats = await original(); change(stats); return stats; };
    peer.connect(); peer.emitTrack(); peer.packets = 1; await env.tick(); peer.packets = 2; await env.tick();
    assert.equal(env.player.ready, true); env.message('audioState', { enabled: true });
    for (let i = 0; i < 4; i++) { peer.packets++; await env.tick(); }
    assert.equal(env.player.enabled, true); assert.equal(env.signals.filter(s => s.type === 'audioAlive').length, 1);
    assert.doesNotMatch(JSON.stringify([env.player.getDiagnostics(), env.states]), /100\.99|8\.8\.8|2001:db8|192\.168|candidate:|ice-ufrag/);
    env.player.dispose();
  }
});

test('candidate metadata cannot authorize a peer still negotiating DTLS', async () => {
  const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
  const peer = env.peers[0]; peer.iceConnectionState = 'connected'; peer.connectionState = 'connecting'; peer.emitTrack();
  peer.packets = 1; await env.tick(); peer.packets = 2; await env.tick();
  assert.equal(env.player.ready, false); assert.equal(env.player.getDiagnostics().setup.stage, 'dtls');
  peer.connect(); assert.equal(env.player.ready, true); env.player.dispose();
});

test('loss of candidate diagnostics never tears down flowing audio, but a real RTP stall does', async () => {
  const env = audioEnvironment(); await env.enable(); const peer = env.peers[0];
  peer.getStats = async () => new Map([['in', { type: 'inbound-rtp', kind: 'audio', packetsReceived: peer.packets }]]);
  peer.packets++; await env.tick(); assert.equal(env.player.enabled, true);
  for (let n = 0; n < 12; n++) await env.tick();
  assert.equal(env.player.enabled, false); assert.match(env.states.at(-1).message, /stalled/);
});
