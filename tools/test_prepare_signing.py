"""Signing regression tests use a disposable key, never the project's private backup."""
import base64
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_signing


class SigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = tempfile.TemporaryDirectory(prefix='pdfcraft-signing-test-',
            dir='/tmp/opencode' if Path('/tmp/opencode').is_dir() else None)
        cls.directory = Path(cls.root.name)
        cls.backup = cls.directory / 'private'
        with redirect_stdout(io.StringIO()):
            prepare_signing.create(cls.backup)
        cls.secret = (cls.backup / 'github-secret.txt').read_text()
        cls.expected = cls.backup / 'certificate.sha256'

    @classmethod
    def tearDownClass(cls):
        cls.root.cleanup()

    def setUp(self):
        self.work = tempfile.TemporaryDirectory(dir=self.directory)
        self.output = Path(self.work.name) / 'ci' / 'key.p12'
        self.env_file = Path(self.work.name) / 'github-env'

    def tearDown(self):
        self.work.cleanup()

    def restore(self, secret=None, expected=None):
        with patch.dict(os.environ, PDFCRAFT_SIGNING_BUNDLE=self.secret if secret is None else secret):
            with redirect_stdout(io.StringIO()):
                prepare_signing.restore(expected or self.expected, self.output, self.env_file)

    def test_same_key_and_certificate_survive_two_separate_restores(self):
        original = (self.backup / 'PDFCraft-signing.p12').read_bytes()
        for _ in range(2):
            self.restore()
            self.assertEqual(original, self.output.read_bytes())
            values = dict(line.split('=', 1) for line in self.env_file.read_text().splitlines())
            self.assertEqual(str(self.output.resolve()), values['PDFCRAFT_SIGNING_STORE_FILE'])
            self.assertEqual(self.expected.read_text().strip(), prepare_signing.certificate(
                self.output, values['PDFCRAFT_SIGNING_STORE_PASSWORD'], values['PDFCRAFT_SIGNING_ALIAS']))
            self.output.unlink()

    def test_missing_secret_fails_without_creating_a_key(self):
        with self.assertRaisesRegex(ValueError, 'No replacement key'):
            self.restore(secret='')
        self.assertFalse(self.output.exists())

    def test_malformed_secret_is_rejected_without_echoing_it(self):
        for value in ('not-base64!', base64.b64encode(b'[]').decode()):
            with self.assertRaisesRegex(ValueError, '^Invalid signing bundle'):
                self.restore(secret=value)
        self.assertFalse(self.output.exists())

    def test_wrong_certificate_is_rejected_and_temporary_key_removed(self):
        expected = Path(self.work.name) / 'wrong.sha256'
        expected.write_text('0' * 64)
        with self.assertRaisesRegex(ValueError, 'certificate does not match'):
            self.restore(expected=expected)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.env_file.exists())

    def test_password_newlines_cannot_inject_github_environment(self):
        bundle = json.loads(base64.b64decode(self.secret))
        bundle['store_password'] = 'password\nINJECTED=true'
        encoded = base64.b64encode(json.dumps(bundle).encode()).decode()
        with self.assertRaisesRegex(ValueError, '^Invalid signing bundle'):
            self.restore(secret=encoded)
        self.assertFalse(self.output.exists())

    def test_existing_keys_are_never_overwritten_or_deleted(self):
        self.restore()
        original = self.output.read_bytes()
        with self.assertRaises(FileExistsError):
            self.restore()
        self.assertEqual(original, self.output.read_bytes())
        with self.assertRaises(FileExistsError):
            prepare_signing.create(self.backup)


if __name__ == '__main__':
    unittest.main()
