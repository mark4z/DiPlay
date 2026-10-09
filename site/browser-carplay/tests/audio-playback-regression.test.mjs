import test from 'node:test';
import assert from 'node:assert/strict';
import { audioEnvironment, deferred, flush } from './audio-fixtures.mjs';
const readyCount = env => env.signals.filter(s => s.type === 'audioReady').length;
const receive = async env => {
  await env.offer(); env.peers[0].connect(); env.peers[0].emitTrack();
  env.peers[0].packets = 1; await env.tick(); env.peers[0].packets = 2; await env.tick();
};

test('a pending empty-stream play gets exactly one populated-stream retry; stale rejection cannot undo success', async () => {
  const first = deferred(), env = audioEnvironment({ playback: first });
  await env.player.enableFromGesture(); const audio = env.audios[0];
  audio.play = function () { this.plays++; assert.equal(this.srcObject.getTracks().length, 1); this.paused = false; return Promise.resolve(); };
  await receive(env); assert.equal(audio.plays, 2); assert.equal(readyCount(env), 1);
  first.reject(new Error('stale initial play')); await flush();
  assert.equal(env.player.ready, true); assert.equal(env.peers.length, 1);
  for (let n = 0; n < 5; n++) { env.peers[0].packets++; await env.tick(); }
  assert.equal(audio.plays, 2); env.player.dispose();
});

test('NotAllowedError preserves route for a synchronous new gesture, without extending setup deadline', async () => {
  const first = deferred(), env = audioEnvironment({ playback: first });
  await env.player.enableFromGesture(); const deadline = [...env.timers].find(([, t]) => t.delay === 15000)[0];
  const audio = env.audios[0]; audio.play = function () { this.plays++; return Promise.reject({ name: 'NotAllowedError', message: 'private detail' }); };
  await receive(env);
  assert.equal(env.player.pending, true); assert.equal(env.player.needsGesture, true);
  assert.equal(env.player.getDiagnostics().setup.playback.state, 'gesture-required');
  assert.equal(readyCount(env), 0); assert.equal(env.modes.length, 1); assert.ok(env.timers.has(deadline));
  assert.doesNotMatch(JSON.stringify(env.states), /private detail/);
  let insideGesture = true;
  audio.play = function () { assert.equal(insideGesture, true); this.plays++; this.paused = false; return Promise.resolve(); };
  assert.equal(env.player.resumePlaybackFromGesture(), true); insideGesture = false;
  await flush(); assert.equal(readyCount(env), 1); assert.equal(env.player.needsGesture, false);
  assert.equal(env.peers.length, 1); assert.equal(env.modes.length, 1); env.player.dispose();
});

test('denied gesture still obeys original timeout and cannot revive after cancellation', async () => {
  const first = deferred(), env = audioEnvironment({ playback: first }); await env.player.enableFromGesture();
  const audio = env.audios[0]; audio.play = () => Promise.reject({ name: 'NotAllowedError' });
  await receive(env); await env.tick(15000);
  assert.equal(env.player.pending, false); assert.equal(env.peers[0].closed, true);
  assert.match(env.states.at(-1).message, /needs another tap/);
  assert.equal(env.player.resumePlaybackFromGesture(), false); assert.equal(readyCount(env), 0);
  first.resolve(); await flush(); assert.equal(env.player.ready, false);
});

test('timeout snapshot distinguishes pending playback from stale RTP and muted/ended track state', async () => {
  const first = deferred(), env = audioEnvironment({ playback: first }); await env.player.enableFromGesture(); await receive(env);
  const current = env.player.getDiagnostics().setup.playback;
  assert.equal(current.state, 'pending'); assert.equal(current.rtpFresh, true); assert.equal(current.paused, true);
  env.player.track.muted = true; env.player.audio.readyState = 2; env.advance(3000);
  const stale = env.player.getDiagnostics().setup.playback;
  assert.equal(stale.rtpFresh, false); assert.equal(stale.trackMuted, true); assert.equal(stale.readyState, 2);
  assert.match(env.player.timeoutMessage(), /audio packets stopped/); env.player.dispose();
});

test('a gesture resume never bypasses local pair, RTP or live-track readiness gates', async () => {
  for (const gate of ['pair', 'rtp', 'track']) {
    const first = deferred(), env = audioEnvironment({ playback: first }); await env.player.enableFromGesture(); await receive(env);
    env.player.requirePlaybackGesture();
    if (gate === 'pair') env.player.localPairVerified = false;
    if (gate === 'rtp') env.advance(3000);
    if (gate === 'track') env.player.track.readyState = 'ended';
    env.audios[0].play = function () { this.paused = false; return Promise.resolve(); };
    env.player.resumePlaybackFromGesture(); await flush(); assert.equal(readyCount(env), 0); env.player.dispose();
  }
});


test('synchronous play failures cannot resume setup, signal enable, leak timers or overwrite errors', async () => {
  const env = audioEnvironment();
  env.player.createAudio = () => ({ paused: true, play() { throw new Error('sync failure'); }, pause() {} });
  assert.equal(await env.player.enableFromGesture(), false);
  assert.equal(env.player.peer, null); assert.equal(env.timers.size, 0);
  assert.equal(env.modes.some(mode => mode.enabled), false);
  assert.match(env.states.at(-1).message, /Playback was blocked/);
  const resume = audioEnvironment(); await resume.player.enableFromGesture();
  resume.player.requirePlaybackGesture();
  resume.audios[0].play = () => { throw new Error('resume sync failure'); };
  assert.equal(resume.player.resumePlaybackFromGesture(), false);
  assert.equal(resume.player.peer, null); assert.equal(resume.timers.size, 0);
  assert.match(resume.states.at(-1).message, /Playback was blocked/);
});
