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
  assert.match(gradle, /sourceSets\.main\.assets\.srcDir\(browserViewerAssets\)/);
  assert.match(gradle, /tasks\.named\('preBuild'\)\.configure\s*\{\s*dependsOn syncBrowserViewerAssets/);
  assert.ok(runtimeAssets.includes('index.html'));
  assert.ok(runtimeAssets.includes('viewer.css'));
  assert.ok(runtimeAssets.includes('viewer.mjs'));
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
  assert.match(html, /connect-src 'self' ws: wss:\/\/tesla\.mark4z\.asia:9999;/);
  assert.doesNotMatch(html, /connect-src[^;]*(?:\*|\bwss:;)/);
});

test('embedded mode never reads endpoint configuration or requests browser microphone capture', () => {
  const source = runtimeAssets.filter(name => name.endsWith('.mjs')).map(read).join('\n');
  assert.doesNotMatch(source, /location\.(?:search|hash)|URLSearchParams|\b(?:localStorage|sessionStorage)\b/);
  assert.doesNotMatch(source, /\b(?:getUserMedia|getDisplayMedia|webkitGetUserMedia|mozGetUserMedia)\b/);
});
