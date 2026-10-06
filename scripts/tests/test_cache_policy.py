"""Credential/cache boundaries use public source and synthetic fixtures only."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
CACHE_ACTION = '0400d5f644dc74513175e3cd8d07132dd4860809'
UPLOAD_ACTION = 'bbbca2ddaa5d8feaa63e36b76fdaad77386f024f'
SAFE_PATHS = [
    '${{ env.GRADLE_USER_HOME }}/wrapper/dists',
    '${{ env.GRADLE_USER_HOME }}/caches/modules-2',
    '${{ env.GRADLE_USER_HOME }}/caches/build-cache-1',
]
TRUSTED_GATE = "github.event_name == 'workflow_dispatch' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/feature/browser-carplay')"
FINGERPRINT = "${{ hashFiles('**/*.gradle*', '**/gradle.properties', 'gradle/**', 'scripts/gradle-readonly-cache.init.gradle') }}"
PREFIX = 'auth-source-original-v1-${{ runner.os }}-${{ runner.arch }}-jdk25-mobile-' + FINGERPRINT + '-'


def steps(job):
    # Keep these guardrails dependency-free; actionlint additionally validates YAML and expressions.
    return re.split(r'^      - ', job, flags=re.MULTILINE)[1:]


class CachePolicyTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / '.github/workflows/build-authenticated-debug.yml').read_text()
        jobs = self.workflow.split('\njobs:\n', 1)[1]
        self.assertEqual(re.findall(r'^  ([a-z-]+):$', jobs, re.MULTILINE), ['source-checks', 'build'])
        self.source, self.auth = jobs.split('\n  build:\n', 1)
        self.source_steps, self.auth_steps = steps(self.source), steps(self.auth)

    def test_single_manual_run_always_stages_and_uploads_one_apk(self):
        dispatch = self.workflow.split('on:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(re.findall(r'^  ([a-z_]+):$', dispatch, re.MULTILINE), ['workflow_dispatch'])
        self.assertNotIn('inputs:', dispatch)
        self.assertNotIn('publish_apk', self.workflow)
        self.assertNotIn('PUBLISH_APK', self.workflow)
        self.assertIn('if: ${{ success() }}', self.auth)
        build = next(step for step in self.auth_steps if 'Build and verify authenticated APK' in step)
        self.assertEqual(build.count('python3 scripts/build_authenticated_debug.py'), 1)
        self.assertIn('--work-dir "$AUTH_WORK_DIR" --publish-dir "$AUTH_APK_DIR"', build)
        self.assertIn('retention-days: 1', self.auth)
        self.assertIn('path: ${{ env.AUTH_APK_DIR }}/DiPlay-standalone-debug.apk', self.auth)
        helper = (ROOT / 'scripts/build_authenticated_debug.py').read_text()
        self.assertIn('":mobile:assembleStandaloneDebug"', helper)
        self.assertNotIn('parser.add_argument("--target"', helper)

    def test_apk_downloads_are_single_unzipped_files_and_reports_stay_bundled(self):
        tv = (ROOT / '.github/workflows/build-android-tv.yml').read_text()
        for workflow, expected_path, retention in (
            (self.auth, '${{ env.AUTH_APK_DIR }}/DiPlay-standalone-debug.apk', 1),
            (tv, 'mobile/build/outputs/apk/debug/mobile-debug.apk', 7),
        ):
            upload_steps = [step for step in steps(workflow) if 'uses: actions/upload-artifact@' in step]
            self.assertEqual(len(upload_steps), 1)
            upload = upload_steps[0]
            self.assertIn('uses: actions/upload-artifact@' + UPLOAD_ACTION, upload)
            self.assertIn('          archive: false', upload)
            self.assertIn('          if-no-files-found: error', upload)
            self.assertIn('          retention-days: ' + str(retention), upload)
            self.assertEqual(re.findall(r'^          path: (.+)$', upload, re.MULTILINE), [expected_path])
            self.assertNotIn('          name:', upload)  # Raw uploads use the actual file basename.
        checks = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('uses: actions/upload-artifact@v4', checks)
        self.assertIn('name: test-reports', checks)
        self.assertNotIn('archive: false', checks)

    def test_only_trusted_manual_source_job_can_produce_this_namespace(self):
        for job in (self.source, self.auth):
            gate = job.split('    if: >-\n', 1)[1].split('    runs-on:', 1)[0]
            self.assertEqual(' '.join(gate.split()), TRUSTED_GATE)
        self.assertNotIn('secrets.', self.source)
        self.assertIn('unset DIPLAY_AUTH_ASSETS_DIR DIPLAY_MFI_KEY_B64 DIPLAY_MFI_CERT_B64', self.source)
        for workflow in (ROOT / '.github/workflows').glob('*.yml'):
            if workflow.name != 'build-authenticated-debug.yml':
                self.assertNotIn('auth-source-original-v1-', workflow.read_text())
        save = next(step for step in self.source_steps if 'uses: actions/cache/save@' in step)
        self.assertIn('if: ${{ success() && ' + TRUSTED_GATE + ' }}', save)

    def test_cached_paths_are_identical_strict_allowlist_not_home_or_build_trees(self):
        cache_steps = [step for step in self.source_steps + self.auth_steps if 'uses: actions/cache/' in step]
        self.assertEqual(len(cache_steps), 3)
        for step in cache_steps:
            self.assertRegex(step, r'uses: actions/cache/(?:restore|save)@' + CACHE_ACTION)
            path = step.split('          path: |\n', 1)[1]
            paths = re.match(r'(?:            .+\n)+', path).group()
            self.assertEqual([line.strip() for line in paths.splitlines()], SAFE_PATHS)
        self.assertEqual(self.workflow.count("printf 'GRADLE_USER_HOME=%s/diplay-gradle\\n'"), 2)

    def test_safe_source_save_finishes_before_fresh_credential_runner_starts(self):
        self.assertIn('needs: source-checks', self.auth)
        source_commands = [step for step in self.source_steps if './gradlew ' in step]
        self.assertEqual(len(source_commands), 1)
        source_step = source_commands[0]
        self.assertEqual(re.findall(r'(?<![\w:]):[\w-]+:\w+', source_step),
                         [':shared:testDebugUnitTest', ':common:testDebugUnitTest', ':mobile:lintDebug'])
        for flag in ('--build-cache', '--no-configuration-cache', '--no-scan'):
            self.assertIn(flag, source_step)
        save_index = next(i for i, step in enumerate(self.source_steps) if 'uses: actions/cache/save@' in step)
        self.assertGreater(save_index, self.source_steps.index(source_step))
        self.assertEqual(save_index, len(self.source_steps) - 1)
        self.assertEqual(self.workflow.count('runs-on: ubuntu-latest'), 2)
        self.assertEqual(self.workflow.count('persist-credentials: false'), 2)

    def test_credential_restore_uses_only_unique_current_run_producer_key(self):
        source_restore = next(step for step in self.source_steps if 'id: source-cache' in step)
        self.assertIn('key: ' + PREFIX + '${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}', source_restore)
        prefixes = source_restore.split('          restore-keys: |\n', 1)[1].strip().splitlines()
        self.assertEqual([line.strip() for line in prefixes], [PREFIX + '${{ github.sha }}-', PREFIX])
        self.assertIn('cache-key: ${{ steps.source-cache.outputs.cache-primary-key }}', self.source)
        save = next(step for step in self.source_steps if 'uses: actions/cache/save@' in step)
        self.assertIn('key: ${{ steps.source-cache.outputs.cache-primary-key }}', save)
        restore = next(step for step in self.auth_steps if 'uses: actions/cache/restore@' in step)
        self.assertIn('key: ${{ needs.source-checks.outputs.cache-key }}', restore)
        self.assertNotIn('restore-keys:', self.auth)
        self.assertNotIn('fail-on-cache-miss: true', self.auth)  # A cold/evicted cache still builds.
        self.assertLess(self.auth_steps.index(restore), next(i for i, step in enumerate(self.auth_steps) if 'secrets.' in step))

    def test_credential_job_has_no_save_hook_or_configuration_snapshot(self):
        self.assertNotIn('actions/cache/save@', self.auth)
        self.assertNotIn('uses: actions/cache@', self.auth)
        self.assertNotIn('setup-gradle', self.auth)
        self.assertNotIn('cache: gradle', self.auth)
        self.assertIn('rm -rf -- "$AUTH_WORK_DIR" "$AUTH_APK_DIR" "$GRADLE_USER_HOME"', self.auth)
        helper = (ROOT / 'scripts/build_authenticated_debug.py').read_text()
        for flag in ('--build-cache', '--no-configuration-cache', '--no-scan', '--init-script'):
            self.assertIn(flag, helper)
        self.assertIn('scripts/gradle-readonly-cache.init.gradle', helper)
        policy = (ROOT / 'scripts/gradle-readonly-cache.init.gradle').read_text()
        self.assertIn('settings.buildCache.local.push = false', policy)
        self.assertIn('settings.buildCache.remote.enabled = false', policy)
        self.assertIn('settings.buildCache.remote.push = false', policy)
        self.assertNotIn('push = true', policy)

    def test_public_source_workflows_preserve_checks_and_block_pull_request_writes(self):
        for name in ('android.yml', 'build-android-tv.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            self.assertNotIn('secrets.', workflow)
            self.assertIn("cache-read-only: ${{ github.event_name != 'push' && github.event_name != 'workflow_dispatch' }}", workflow)
            self.assertIn('gradle-home-cache-includes: |\n            caches/modules-2\n            caches/build-cache-1\n            wrapper/dists', workflow)
            gradle = next(line for line in workflow.splitlines() if 'run: ./gradlew' in line)
            self.assertIn('--build-cache', gradle)
            self.assertIn('--no-configuration-cache', gradle)
        expected_tasks = {
            'android.yml': [':shared:testDebugUnitTest', ':common:testDebugUnitTest', ':home:testDebugUnitTest',
                            ':mobile:lintDebug', ':home:lintDebug', ':maphost:lintDebug',
                            ':mobile:assembleDebug', ':home:assembleDebug', ':maphost:assembleDebug'],
            'build-android-tv.yml': [':shared:testDebugUnitTest', ':common:testDebugUnitTest',
                                     ':mobile:lintDebug', ':mobile:assembleDebug'],
        }
        for name, expected in expected_tasks.items():
            workflow = (ROOT / '.github/workflows' / name).read_text()
            gradle = next(line for line in workflow.splitlines() if 'run: ./gradlew' in line)
            self.assertEqual(re.findall(r'(?<![\w:]):[\w-]+:\w+', gradle), expected)


if __name__ == '__main__':
    unittest.main()
