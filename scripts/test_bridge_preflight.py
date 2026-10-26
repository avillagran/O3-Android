import argparse
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('preflight', Path(__file__).with_name('bridge-preflight.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class PreflightTests(unittest.TestCase):
    def test_only_requested_identity_metadata_is_exported(self):
        raw = json.dumps({'environments': [
            {'id': 'target', 'enabled': True, 'routes': [{'token': 'do-not-export'}], 'name': 'private'},
            {'id': 'another-private-id', 'enabled': True}]})
        result = probe.sanitized_catalog(raw, 'target')
        self.assertEqual(result, {'available': True, 'present': True, 'unique': True, 'enabled': True, 'routeCount': 1})
        self.assertNotIn('private', json.dumps(result))
        self.assertNotIn('do-not-export', json.dumps(result))

    def test_unavailable_is_not_an_authenticated_absence(self):
        self.assertEqual(probe.sanitized_catalog(None, 'target')['present'], None)
        self.assertEqual(probe.sanitized_catalog('{"environments":[]}', 'target')['present'], False)

    def test_credential_bearing_origins_and_redirects_rejected(self):
        for origin in ['https://user:secret@host', 'https://host/?token=secret', 'https://host/#secret', 'file:///etc/passwd']:
            with self.assertRaises(argparse.ArgumentTypeError):
                probe.origin(origin)
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other'))

    def test_remote_stderr_is_never_returned(self):
        with patch.object(probe.subprocess, 'run', return_value=argparse.Namespace(returncode=1, stdout=b'credential', stderr=b'credential')):
            self.assertIsNone(probe.remote('host', ['/fixed/path', 'connections-view']))


if __name__ == '__main__':
    unittest.main()
