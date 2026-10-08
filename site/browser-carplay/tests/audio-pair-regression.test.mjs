import test from 'node:test';
import assert from 'node:assert/strict';
import { audioEnvironment } from './audio-fixtures.mjs';

test('a pending selected local host pair waits without claiming readiness, then verifies on success', async () => {
  const env = audioEnvironment();
  await env.player.enableFromGesture(); await env.offer();
  const peer = env.peers[0], original = peer.getStats.bind(peer);
  let pairState = 'in-progress';
  peer.packets = 0;
  peer.getStats = async () => { const stats = await original(); stats.get('pair').state = pairState; return stats; };
  await env.tick();
  assert.equal(env.player.pending, true);
  assert.equal(env.player.localPairVerified, false);
  assert.equal(env.signals.filter(s => s.type === 'audioReady').length, 0);
  pairState = 'succeeded'; peer.emitTrack(); peer.connect();
  peer.packets = 1; await env.tick();
  assert.equal(env.player.localPairVerified, true);
  assert.equal(env.signals.filter(s => s.type === 'audioReady').length, 1);
  env.player.dispose();
});

test('pending host pair checks preserve the original setup deadline', async () => {
  for (const state of ['frozen', 'waiting', 'in-progress']) {
    const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
    const peer = env.peers[0], original = peer.getStats.bind(peer); peer.packets = 0;
    peer.getStats = async () => { const stats = await original(); stats.get('pair').state = state; return stats; };
    const deadline = [...env.timers].find(([, timer]) => timer.delay === 15000)[0];
    await env.tick(); await env.tick();
    assert.equal(env.player.pending, true); assert.equal(env.timers.has(deadline), true);
    assert.equal(env.signals.filter(s => s.type === 'audioReady').length, 0);
    await env.tick(15000);
    assert.equal(env.player.pending, false); assert.equal(peer.closed, true);
    assert.match(env.states.at(-1).message, /timed out/);
  }
});

test('an active pair losing success still closes immediately', async () => {
  for (const state of ['frozen', 'waiting', 'in-progress', 'failed', 'unknown']) {
    const env = audioEnvironment(); await env.enable();
    const peer = env.peers[0], original = peer.getStats.bind(peer);
    peer.getStats = async () => { const stats = await original(); stats.get('pair').state = state; return stats; };
    await env.tick();
    assert.equal(env.player.enabled, false); assert.equal(peer.closed, true);
    assert.match(env.states.at(-1).message, /selected pair has not succeeded/);
  }
});

test('a checking pair never defers unsafe or unverifiable metadata and records a fixed reason', async () => {
  const changes = [
    [stats => { stats.get('remote').address = '100.99.0.1'; }, /outside local policy/],
    [stats => { stats.get('remote').address = '8.8.8.8'; }, /outside local policy/],
    [stats => { stats.get('remote').candidateType = 'prflx'; }, /type is not host/],
    [stats => { stats.get('local').candidateType = 'relay'; }, /type is not host/],
    [stats => { delete stats.get('local').address; }, /address unavailable/],
    [stats => { delete stats.get('remote').protocol; }, /protocol is missing or mismatched/],
    [stats => { stats.get('remote').protocol = 'tcp'; }, /protocol is missing or mismatched/],
    [stats => { stats.delete('remote'); }, /candidate details missing/],
  ];
  for (const state of ['in-progress', 'succeeded']) for (const [change, reason] of changes) {
    const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
    const peer = env.peers[0], original = peer.getStats.bind(peer); peer.packets = 7;
    peer.getStats = async () => { const stats = await original(); stats.get('pair').state = state; change(stats); return stats; };
    await env.tick();
    assert.equal(env.player.pending, false); assert.equal(peer.closed, true);
    assert.equal(env.signals.filter(s => s.type === 'audioReady').length, 0);
    assert.match(env.states.at(-1).message, reason);
    const diagnostics = env.player.getDiagnostics();
    assert.equal(diagnostics.packetsReceived, 7);
    assert.ok(diagnostics.selectedCandidatePair);
    assert.doesNotMatch(JSON.stringify([diagnostics, env.states]), /192\.168|100\.99|8\.8\.8|candidate:|ice-pwd/);
  }
});

test('failed or unknown selected-pair states are not treated as pending', async () => {
  for (const state of ['failed', 'unknown', undefined]) {
    const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
    const peer = env.peers[0], original = peer.getStats.bind(peer); peer.packets = 0;
    peer.getStats = async () => { const stats = await original(); stats.get('pair').state = state; return stats; };
    await env.tick(); assert.equal(env.player.pending, false); assert.equal(peer.closed, true);
    assert.match(env.states.at(-1).message, /selected pair has not succeeded/);
  }
});

test('a ready route losing all pair stats fails closed with a fixed reason', async () => {
  const env = audioEnvironment(); await env.receiving();
  const peer = env.peers[0]; peer.getStats = async () => new Map();
  await env.tick(); assert.equal(peer.closed, true); assert.equal(env.player.ready, false);
  assert.match(env.states.at(-1).message, /selected pair missing/);
});
