import test from 'node:test';
import assert from 'node:assert/strict';
import { audioEnvironment, candidate } from './audio-fixtures.mjs';
const count = env => env.signals.filter(s => s.type === 'audioReady').length;
const event = (address = 'receiver-private.local', extra = {}) => ({ ...candidate,
  candidate: candidate.candidate.replace('192.168.1.20', address), usernameFragment: 'test', ...extra });
async function setup({ events = [event()], mutate = () => {} } = {}) {
  const env = audioEnvironment(); await env.player.enableFromGesture(); await env.offer();
  const peer = env.peers[0];
  for (const value of events) peer.onicecandidate({ candidate: value });
  peer.emitTrack(); peer.connect();
  const original = peer.getStats.bind(peer);
  peer.getStats = async () => {
    const stats = await original();
    Object.assign(stats.get('local'), { id: 'local', transportId: 'transport', address: '',
      port: 4444, foundation: '1', priority: 2122260223, usernameFragment: 'test' });
    Object.assign(stats.get('pair'), { id: 'pair', transportId: 'transport' });
    Object.assign(stats.get('transport'), { id: 'transport' });
    mutate(stats); return stats;
  };
  peer.packets = 1; await env.tick();
  if (env.player.pending) { peer.packets = 2; await env.tick(); }
  return env;
}

test('empty local address requires exact unique current signaled local correlation; mDNS and private literal work', async () => {
  for (const address of ['receiver-private.local', '192.168.1.20']) {
    const env = await setup({ events: [event(address)] });
    assert.equal(count(env), 1); assert.equal(env.player.localPairVerified, true);
    assert.doesNotMatch(JSON.stringify([env.player.getDiagnostics(), env.states]), /receiver-private|192\.168|candidate:|ice-ufrag/);
    env.player.dispose(); assert.equal(env.player.observedLocalCandidates.length, 0);
  }
});

test('missing stats correlation metadata and mismatched IDs, ufrag, foundation, priority, port fail closed', async () => {
  const mutations = [stats => { delete stats.get('local').port; }, stats => { stats.get('local').port = 0; },
    stats => { delete stats.get('local').foundation; }, stats => { delete stats.get('local').usernameFragment; },
    stats => { stats.get('local').id = 'other'; }, stats => { stats.get('local').transportId = 'other'; },
    stats => { stats.get('pair').id = 'other'; }, stats => { stats.get('pair').transportId = 'other'; },
    stats => { stats.get('local').usernameFragment = 'stale'; }, stats => { stats.get('local').foundation = '2'; },
    stats => { stats.get('local').priority--; }, stats => { stats.get('local').port++; }];
  for (const mutate of mutations) {
    const env = await setup({ mutate }); assert.equal(count(env), 0); assert.equal(env.peers[0].closed, true);
    assert.match(env.states.at(-1).message, /address hidden; proof=correlation-/);
  }
});

test('no observation, ambiguous allowed observations and colliding rejected public observations cannot prove hidden address', async () => {
  for (const events of [[], [event(), event('other-private.local')], [event(), event('8.8.8.8')], [event('8.8.8.8')]]) {
    const env = await setup({ events }); assert.equal(count(env), 0); assert.equal(env.peers[0].closed, true);
    assert.match(env.states.at(-1).message, /correlation-(unmatched|ambiguous|disallowed)/);
  }
  const duplicate = await setup({ events: [event(), event()] }); assert.equal(count(duplicate), 1); duplicate.player.dispose();
});

test('bad mid, stale ufrag, malformed/overflow observations fail closed without weakening known-address behavior', async () => {
  const overflowing = Array.from({ length: 33 }, (_, i) => event(`203.0.113.${i + 1}`));
  for (const events of [[event('receiver-private.local', { sdpMid: 'other' })],
    [event('receiver-private.local', { usernameFragment: 'old-generation' })],
    [event('receiver-private.local', { candidate: 'malformed' })], overflowing]) {
    const env = await setup({ events }); assert.equal(count(env), 0); assert.equal(env.peers[0].closed, true);
    assert.match(env.states.at(-1).message, /observation-incomplete/);
  }
});

test('host types, valid remote address, protocol and pair success are still mandatory', async () => {
  for (const mutate of [stats => { stats.get('local').candidateType = 'prflx'; },
    stats => { stats.get('remote').candidateType = 'prflx'; }, stats => { stats.get('remote').candidateType = 'relay'; },
    stats => { delete stats.get('local').candidateType; }, stats => { stats.get('remote').address = ''; },
    stats => { stats.get('remote').address = '8.8.8.8'; }, stats => { stats.get('remote').address = '100.99.9.9'; },
    stats => { stats.get('local').address = '100.99.9.9'; }, stats => { stats.get('local').protocol = 'tcp'; },
    stats => { stats.get('pair').state = 'failed'; }]) {
    const env = await setup({ mutate }); assert.equal(count(env), 0); assert.equal(env.peers[0].closed, true);
  }
  const pending = await setup({ mutate: stats => { stats.get('pair').state = 'in-progress'; } });
  assert.equal(count(pending), 0); assert.equal(pending.player.pending, true); pending.player.dispose();
});

test('type rejection labels distinguish the endpoint and missing type without exposing raw fields', async () => {
  for (const type of ['prflx', 'srflx', 'relay', undefined, 'untrusted-raw']) for (const side of ['local', 'remote']) {
    const env = await setup({ mutate: stats => { stats.get(side).candidateType = type; } });
    const label = ['prflx', 'srflx', 'relay'].includes(type) ? type : 'unavailable';
    assert.ok(env.states.at(-1).message.includes(side === 'local' ? `browser=${label}, Android=host` : `browser=host, Android=${label}`));
    assert.doesNotMatch(JSON.stringify([env.player.getDiagnostics(), env.states]), /untrusted-raw|receiver-private|192\.168/);
  }
});

test('old-generation observations cannot prove a replacement peer and changed selected IDs fail closed', async () => {
  const env = await setup(); assert.equal(count(env), 1); const oldCallback = env.peers[0].onicecandidate;
  env.player.disable(); await env.player.enableFromGesture(); await env.offer({ epoch: 2 });
  oldCallback({ candidate: event() }); assert.equal(env.player.observedLocalCandidates.length, 0);
  assert.equal(env.player.localObservationIncomplete, false); env.player.dispose();
  let change = false;
  const migrated = await setup({ mutate: stats => { if (change) stats.get('local').port++; } });
  assert.equal(count(migrated), 1); change = true; migrated.peers[0].packets++; await migrated.tick();
  assert.equal(migrated.player.ready, false); assert.equal(migrated.peers[0].closed, true);
});


test('non-null end-of-generation markers before and after readiness do not invalidate correlated proof', async () => {
  const end = { candidate: '', sdpMid: '0', sdpMLineIndex: 0, usernameFragment: 'test' };
  const env = await setup({ events: [event(), end] });
  assert.equal(count(env), 1); assert.equal(env.player.localObservationIncomplete, false);
  assert.equal(env.player.observedLocalCandidates.length, 1);
  const signals = env.signals.length;
  env.peers[0].onicecandidate({ candidate: end }); env.peers[0].onicecandidate({ candidate: null });
  env.peers[0].packets++; await env.tick();
  assert.equal(env.player.ready, true); assert.equal(env.player.localObservationIncomplete, false);
  assert.equal(env.signals.length, signals); assert.equal(env.player.observedLocalCandidates.length, 1);
  env.player.dispose();
});
