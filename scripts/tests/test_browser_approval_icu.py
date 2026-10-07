"""Run the actual Kotlin approval patterns on ICU, as Android does (not JVM regex).

Uses the system ICU library present on the Ubuntu Actions runner. Missing ICU is
an error, not a skipped regression: desktop Java accepts the original bad regex.
"""
import ctypes
import ctypes.util
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'shared/src/main/java/com/shilapi/xcertplay/browser/BrowserLanServer.kt'


class ApprovalIcuTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        library = ctypes.util.find_library('icui18n')
        if not library:
            raise RuntimeError('Native ICU is required for Android regex regression coverage')
        cls.icu = ctypes.CDLL(library)
        # ICU exports version-suffixed symbols on Linux (e.g. uregex_open_74).
        def symbol(name):
            for suffix in [''] + [f'_{version}' for version in range(50, 100)]:
                try:
                    return getattr(cls.icu, name + suffix)
                except AttributeError:
                    pass
            raise RuntimeError(f'ICU symbol unavailable: {name}')
        cls.open = symbol('uregex_open')
        cls.open.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32,
                            ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
        cls.open.restype = ctypes.c_void_p
        cls.close = symbol('uregex_close')
        cls.close.argtypes = [ctypes.c_void_p]
        cls.set_text = symbol('uregex_setText')
        cls.set_text.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32,
                                ctypes.POINTER(ctypes.c_int32)]
        cls.matches = symbol('uregex_matches')
        cls.matches.argtypes = [ctypes.c_void_p, ctypes.c_int32,
                               ctypes.POINTER(ctypes.c_int32)]
        cls.matches.restype = ctypes.c_int8
        cls.patterns = re.findall(r'Regex\("""(.*?)"""\)', SOURCE.read_text())

    def compile(self, pattern):
        encoded = pattern.encode('utf-16-le')
        buffer = ctypes.create_string_buffer(encoded)
        status = ctypes.c_int32(0)
        handle = self.open(buffer, len(encoded) // 2, 0, None, ctypes.byref(status))
        return handle, status.value

    def test_actual_approval_patterns_compile_and_match_on_icu(self):
        self.assertEqual(len(self.patterns), 2)
        good = ['{"type":"requestApproval","version":2}',
                ' { "version": 2, "type": "requestApproval" } ']
        bad = ['{"type":"auth","token":"legacy"}',
               '{"type":"requestApproval","version":1}',
               '{"type":"requestApproval","version":"2"}',
               '{"type":"requestApproval","version":2.0}',
               '{"type":"requestApproval","version":2,"extra":1}',
               '{"type":"requestApproval","type":"requestApproval"}',
               '{"type":"requestApproval","version":2,"version":2}',
               '{"type":"requestApproval","version":{"value":2}}',
               good[0] + ' garbage', good[0][:-1]]
        accepted = set()
        for pattern in self.patterns:
            handle, status = self.compile(pattern)
            try:
                self.assertEqual(status, 0, f'ICU compilation failed ({status}): {pattern}')
                self.assertTrue(handle)
                for text in good + bad:
                    encoded = text.encode('utf-16-le')
                    buffer = ctypes.create_string_buffer(encoded)
                    error = ctypes.c_int32(0)
                    self.set_text(handle, buffer, len(encoded) // 2, ctypes.byref(error))
                    matched = self.matches(handle, 0, ctypes.byref(error))
                    self.assertEqual(error.value, 0)
                    if matched:
                        accepted.add(text)
            finally:
                if handle:
                    self.close(handle)
        self.assertEqual(accepted, set(good))

    def test_icu_rejects_the_unescaped_closing_brace(self):
        # Proves this test engine catches the Android-only failure, unlike the JVM.
        handle, status = self.compile(r'\{"version":2}')
        if handle:
            self.close(handle)
        self.assertEqual(status, 66305)  # U_REGEX_RULE_SYNTAX


if __name__ == '__main__':
    unittest.main()
