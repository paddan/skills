import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'skills/jfr-analyzer/scripts'


class JfrScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.bin = self.base / 'bin'
        self.bin.mkdir()
        self.recording = self.base / 'recording.jfr'
        self.recording.write_bytes(b'recording must survive')
        self.env = dict(os.environ, PATH=f'{self.bin}{os.pathsep}{os.environ["PATH"]}')
        self.tool('java', 'echo partial-report\nexit "${JAVA_EXIT:-0}"\n')
        self.tool('jfr', '''case "$1" in
  --version) echo 21 ;;
  help) exit "${NO_VIEW:-0}" ;;
  summary)
    if [ "${FAIL_CORE:-0}" = 1 ]; then echo corrupt >&2; exit 9; fi
    printf ' Event Type Count Size\n jdk.ExecutionSample 1 1\n' ;;
  metadata) echo metadata ;;
  view|print)
    if [ "${FAIL_REPORTS:-0}" = 1 ]; then echo partial; echo diagnostic >&2; exit 7; fi
    echo 'No events found' ;;
esac
''')

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/bash\n' + body)
        path.chmod(0o755)

    def run_script(self, script, *args):
        return subprocess.run(['bash', str(SCRIPTS / script), *map(str, args)],
                              env=self.env, text=True, capture_output=True)

    def test_input_aliases_are_rejected_without_damage(self):
        symlink = self.base / 'symlink.jfr'
        symlink.symlink_to(self.recording)
        hardlink = self.base / 'hardlink.jfr'
        os.link(self.recording, hardlink)
        for output in (self.recording, symlink, hardlink):
            with self.subTest(output=output):
                result = self.run_script('analyze-package.sh', self.recording, 'example', output)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.recording.read_bytes(), b'recording must survive')

    def test_failed_analysis_preserves_existing_report(self):
        output = self.base / 'report.md'
        output.write_text('previous report')
        self.env['JAVA_EXIT'] = '7'
        result = self.run_script('analyze-package.sh', self.recording, 'example', output)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(output.read_text(), 'previous report')
        self.assertEqual(list(self.base.glob('.package-report.*')), [])

    def test_success_replaces_report_atomically(self):
        output = self.base / 'report.md'
        output.write_text('previous report')
        result = self.run_script('analyze-package.sh', self.recording, 'example', output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), 'partial-report\n')

    def test_view_and_fallback_errors_are_visible(self):
        for no_view in ('0', '1'):
            with self.subTest(no_view=no_view):
                self.env.update(FAIL_REPORTS='1', NO_VIEW=no_view)
                output = self.base / f'reports-{no_view}'
                result = self.run_script('analyze-jfr.sh', self.recording, output)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('INCOMPLETE', (output / 'INDEX.md').read_text())
                self.assertIn('\t7\t', (output / 'report-status.tsv').read_text())
                self.assertTrue(any('diagnostic' in p.read_text() for p in output.rglob('*.stderr')))

    def test_empty_success_is_not_reported_as_failure(self):
        output = self.base / 'reports'
        result = self.run_script('analyze-jfr.sh', self.recording, output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('COMPLETE', (output / 'INDEX.md').read_text())
        self.assertNotIn('INCOMPLETE', (output / 'INDEX.md').read_text())

    def test_existing_output_is_preserved(self):
        output = self.base / 'reports'
        output.mkdir()
        marker = output / 'summary.txt'
        marker.write_text('previous results')
        result = self.run_script('analyze-jfr.sh', self.recording, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(marker.read_text(), 'previous results')

    def test_core_error_is_in_index(self):
        self.env['FAIL_CORE'] = '1'
        output = self.base / 'reports'
        result = self.run_script('analyze-jfr.sh', self.recording, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('summary.txt', (output / 'INDEX.md').read_text())
        self.assertIn('INCOMPLETE', (output / 'INDEX.md').read_text())
        self.assertEqual((output / 'summary.txt.stderr').read_text(), 'corrupt\n')


if __name__ == '__main__':
    unittest.main()
