import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';

const viewerRoot = new URL('../', import.meta.url);
const read = path => readFileSync(new URL(path, viewerRoot), 'utf8');
const runtimeAssets = readdirSync(viewerRoot).filter(name => name === 'index.html' || /\.(?:css|mjs)$/.test(name));

test('Gradle copies canonical root runtime assets into the APK before Android preBuild', () => {
  // Source-contract validation only: the Android/Gradle build runs separately.
  const gradle = read('../../shared/build.gradle');
  assert.match(gradle, /tasks\.register\('syncBrowserViewerAssets', Sync\)/);
  assert.match(gradle, /from\(rootProject\.file\('site\/browser-carplay'\)\)/);
  assert.match(gradle, /include 'index\.html', '\*\.css', '\*\.mjs'/);
  assert.match(gradle, /exclude 'README\*', 'tests\/\*\*'/);
  assert.match(gradle, /into 'browser-carplay'/);
  assert.match(gradle, /sourceSets\.main\.assets\.srcDir\(browserViewerAssets\.get\(\)\.asFile\)/);
  assert.match(gradle, /tasks\.named\('preBuild'\)\.configure\s*\{\s*dependsOn syncBrowserViewerAssets/);
  assert.ok(runtimeAssets.includes('index.html'));
  assert.ok(runtimeAssets.includes('viewer.css'));
  assert.ok(runtimeAssets.includes('viewer.mjs'));
  assert.ok(runtimeAssets.includes('config.mjs'));
  assert.match(gradle, /environmentVariable\('DIPLAY_HTTPS_DOMAIN'\)/);
  assert.match(gradle, /US_ASCII\.newEncoder\(\)\.canEncode\(value\)/);
  assert.ok(gradle.indexOf('US_ASCII.newEncoder().canEncode(value)') < gradle.indexOf('value.trim().toLowerCase'),
    'reject non-ASCII input before Unicode case folding can convert it to ASCII');
  assert.match(gradle, /sourceSets\.main\.resources\.srcDir\(browserHttpsResources\.get\(\)\.asFile\)/);
  assert.doesNotMatch(gradle, /sourceSets\.main\.resources\.srcDir\(browserHttpsResources\)/,
    'Android source-set API requires the resolved File, not a Provider');
  assert.match(gradle, /dependsOn generateBrowserHttpsConfig/);
  assert.equal(runtimeAssets.includes('README.md'), false);
  assert.equal(runtimeAssets.includes('tests'), false);
  for (const name of runtimeAssets) assert.ok(statSync(new URL(name, viewerRoot)).isFile());
});

test('every stylesheet, script and module dependency resolves inside the packaged viewer', () => {
  const html = read('index.html');
  const dependencies = [...html.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)="([^"]+)"/g)].map(match => match[1]);
  for (const name of runtimeAssets.filter(name => name.endsWith('.mjs'))) {
    for (const match of read(name).matchAll(/\b(?:from\s*|import\s*\()(['"])([^'"]+)\1/g)) dependencies.push(match[2]);
  }
  assert.ok(dependencies.length >= 9, 'exercise the full ES-module dependency graph');
  for (const dependency of dependencies) {
    assert.doesNotMatch(dependency, /^(?:[a-z]+:|\/|\.\.\/)/i, dependency);
    const resolved = new URL(dependency, viewerRoot);
    assert.equal(new URL('.', resolved).href, viewerRoot.href, dependency);
    assert.ok(runtimeAssets.includes(resolved.pathname.split('/').at(-1)), dependency);
  }
  assert.doesNotMatch(read('viewer.css'), /@import|url\s*\(/i, 'no remote fonts, stylesheets, or image dependencies');
  // ws: already permits its secure upgrade. APK responses further intersect
  // this external-viewer policy with an exact-host, wss-only CSP header.
  assert.match(html, /connect-src 'self' ws:;/);
  assert.doesNotMatch(html, /tesla\.mark4z\.asia/);
  assert.doesNotMatch(html, /connect-src[^;]*(?:\*|\bwss:;)/);
});

test('browser and Android use a single immutable build hostname rather than source edits', () => {
  const core = read('core.mjs');
  const native = read('../../shared/src/main/java/com/shilapi/xcertplay/browser/BrowserViewerAssets.kt');
  const controls = read('../../common/src/main/java/com/shilapi/xcertplay/BrowserHttpsControls.kt');
  assert.match(core, /import \{ HTTPS_HOSTNAME \} from '\.\/config\.mjs'/);
  assert.doesNotMatch(core, /tesla\.mark4z\.asia/);
  assert.match(native, /val HOSTNAME = BrowserHttpsPolicy\.HOSTNAME/);
  assert.match(native, /name == "config\.mjs"\) configurationModule\(\)/);
  assert.doesNotMatch(native, /tesla\.mark4z\.asia/);
  assert.match(controls, /\$\{BrowserHttpsPolicy\.HOSTNAME\}/);
  assert.doesNotMatch(controls, /tesla\.mark4z\.asia/);
});

test('embedded mode never reads endpoint configuration or requests browser microphone capture', () => {
  const source = runtimeAssets.filter(name => name.endsWith('.mjs')).map(read).join('\n');
  assert.doesNotMatch(source, /location\.(?:search|hash)|URLSearchParams|\b(?:localStorage|sessionStorage)\b/);
  assert.doesNotMatch(source, /\b(?:getUserMedia|getDisplayMedia|webkitGetUserMedia|mozGetUserMedia)\b/);
});

test('Android serving allowlist contains every imported browser runtime asset', () => {
  const native = read('../../shared/src/main/java/com/shilapi/xcertplay/browser/BrowserViewerAssets.kt');
  const names = native.match(/val NAMES = listOf\(([\s\S]*?)\)/)?.[1] || '';
  for (const name of runtimeAssets) assert.ok(names.includes(`"${name}"`), `Android must serve ${name}`);
});


test('packaged viewer has no audio forwarding assets, APIs, controls, or diagnostics', () => {
  assert.equal(runtimeAssets.some(name => /audio/i.test(name)), false);
  const scripts = runtimeAssets.filter(name => name.endsWith('.mjs')).map(read).join('\n');
  assert.doesNotMatch(scripts, /RTCPeerConnection|RTCSessionDescription|RTCIceCandidate|MediaStream|AudioContext|AudioWorklet|BrowserAudio|onAudio|setAudio|sendAudio|getAudioDiagnostics|audioMode|audioReady|audioAlive|audioOffer|audioAnswer|audioIce|localOnly|localPolicy|candidatePair/i);
  assert.doesNotMatch(scripts, /createElement\(['"]audio['"]\)|new Audio\s*\(/);
  const html = read('index.html');
  assert.doesNotMatch(html, /id="(?:audio(?:-[^"]*)?|compact-audio-error)"|<audio|Play audio here|Test audio|fullscreen \+ audio/i);
  assert.match(html, /media-src 'none';/);
  assert.match(html, /Enter fullscreen<\/button>/);
  assert.match(html, /phone’s direct connection to the car/);
  assert.doesNotMatch(read('viewer.css'), /audio-toolbar|audio-status|compact-audio-error/);
});


test('native resolution checkbox keeps visible contrast and an explicit state label', () => {
  assert.match(read('viewer.css'), /input\[type="checkbox"\][^}]*accent-color: #2563eb/);
  assert.match(read('index.html'), /id="resolution-follow"[^>]*checked[^>]*>[^<]*<span id="resolution-follow-state" aria-hidden="true">On<\/span>/);
});
