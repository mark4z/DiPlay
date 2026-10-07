#!/usr/bin/env python3
"""Exercise source-cache hits and read-only misses in an isolated synthetic Gradle project."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import os

ROOT = Path(__file__).resolve().parents[1]
BUILD = '''
@CacheableTask
abstract class CacheProbe extends DefaultTask {
    @Input abstract Property<String> getPayload()
    @OutputFile abstract RegularFileProperty getResultFile()
    @TaskAction void generate() {
        resultFile.get().asFile.text = payload.get()
        logger.lifecycle("CACHE_PROBE_EXECUTED")
    }
}
tasks.register("probe", CacheProbe) {
    payload = providers.gradleProperty("payload").orElse("synthetic-source")
    resultFile = layout.buildDirectory.file("probe.txt")
}
tasks.register("verifyReadOnly") {
    doLast {
        def cache = gradle.cacheProbeSettings.buildCache
        assert cache.local.enabled
        assert !cache.local.push
        assert !cache.remote.enabled
        assert !cache.remote.push
    }
}
'''
SETTINGS = '''
rootProject.name = "synthetic-cache-policy-test"
gradle.ext.cacheProbeSettings = settings
// This endpoint must never be contacted. Source runs disable it; the init script must
// override a remote enabled by a settings file before a credential-stage task starts.
buildCache {
    remote(HttpBuildCache) {
        url = uri("https://cache.invalid/")
        enabled = providers.gradleProperty("testRemoteOverride").isPresent()
        push = true
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gradle', type=Path, required=True, help='Existing installed Gradle executable')
    args = parser.parse_args()
    executable = args.gradle.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix='diplay-synthetic-cache-') as temporary:
        base = Path(temporary)
        project, home = base / 'project', base / 'gradle-home'
        project.mkdir()
        (project / 'settings.gradle').write_text(SETTINGS)
        (project / 'build.gradle').write_text(BUILD)
        # Never inherit authentication/signing inputs into this fixture.
        environment = {name: os.environ[name] for name in ('PATH', 'JAVA_HOME', 'SYSTEMROOT') if name in os.environ}
        environment.update(HOME=str(base), GRADLE_USER_HOME=str(home))
        common = [str(executable), '--offline', '--no-daemon', '--no-configuration-cache',
                  '--build-cache', '--no-scan', '--console=plain', '--max-workers=1']
        policy = str(ROOT / 'scripts/gradle-readonly-cache.init.gradle')

        def run(read_only=False, payload='synthetic-source'):
            shutil.rmtree(project / 'build', ignore_errors=True)
            command = common + ['-Ppayload=' + payload]
            if read_only:
                command += ['--init-script', policy, '-PtestRemoteOverride=true', 'verifyReadOnly']
            command += ['probe']
            result = subprocess.run(command, cwd=project, env=environment, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
            if result.returncode:
                raise RuntimeError(result.stdout)
            assert (project / 'build/probe.txt').read_text() == payload
            return result.stdout

        def entries():
            return {path.name: path.read_bytes() for path in (home / 'caches/build-cache-1').glob('*')
                    if path.is_file() and re.fullmatch(r'[0-9a-f]{32}', path.name)}

        cold = run()
        assert 'CACHE_PROBE_EXECUTED' in cold
        original = entries()
        assert original, 'Cold source execution must populate the local task-output cache'
        warm = run(read_only=True)
        assert ':probe FROM-CACHE' in warm and 'CACHE_PROBE_EXECUTED' not in warm
        assert entries() == original
        miss = run(read_only=True, payload='synthetic-alternate')
        assert 'CACHE_PROBE_EXECUTED' in miss and ':probe FROM-CACHE' not in miss
        assert entries() == original, 'A read-only miss must not save task outputs'
        second_miss = run(read_only=True, payload='synthetic-alternate')
        assert 'CACHE_PROBE_EXECUTED' in second_miss and ':probe FROM-CACHE' not in second_miss
        assert entries() == original
        print('PASS: cold source save, warm read-only FROM-CACHE, two read-only misses with zero new cache entries, remote cache disabled.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
