#!/usr/bin/env python3
"""Private, bounded read-only NODE follow acceptance harness. Never prints a bearer."""
import argparse
import http.server
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import threading


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--t3-bin', required=True)
    parser.add_argument('--base-dir', required=True)
    parser.add_argument('--server-url', required=True, help='Address reachable by the isolated emulator')
    parser.add_argument('--state-dir', help='Private directory outside the repository; default is a new /tmp directory')
    args = parser.parse_args()
    root = Path(args.state_dir) if args.state_dir else Path(tempfile.mkdtemp(prefix='ot3-follow-qa-'))
    if root.resolve().is_relative_to(Path(__file__).resolve().parents[1]):
        parser.error('--state-dir must be outside the repository')
    if (root / 'session.json').exists():
        parser.error('state directory already holds a session; finish/revoke that session first')
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(root, 0o700)
    command = [args.t3_bin, 'auth', 'session']
    issued = subprocess.run(command + ['issue', '--base-dir', str(Path(args.base_dir).expanduser()),
        '--scope', 'orchestration:read', '--ttl', '30m', '--label', 'ot3-follow-readonly-emulator', '--json'],
        capture_output=True, text=True, check=True)
    session = json.loads(issued.stdout)
    secret = root / 'session.json'
    with os.fdopen(os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as output:
        json.dump(session, output)
    state = {'ready': False}
    release = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass
        def output(self, value):
            data = json.dumps(value).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        def do_GET(self):
            if self.path == '/config':
                self.output({'url': args.server_url, 'token': session['token'], 'revokeAfterCapture': True})
            elif self.path == '/status':
                self.output(state)
            elif self.path == '/continue':
                release.wait(210)
                self.output({'released': release.is_set()})
            else:
                self.send_error(404)
        def do_POST(self):
            if self.path == '/ready':
                state.update(json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0')))))
                state['ready'] = True
                (root / 'verification.json').write_text(json.dumps(state, indent=2) + '\n')
                self.output({'ok': True})
            elif self.path == '/revoke':
                revoked = subprocess.run(command + ['revoke', '--base-dir', str(Path(args.base_dir).expanduser()), session['sessionId']],
                    capture_output=True, text=True)
                state['credentialRevoked'] = revoked.returncode == 0
                self.output({'revoked': state['credentialRevoked']})
            elif self.path == '/release':
                release.set()
                self.output({'ok': True})
            elif self.path == '/finish':
                release.set()
                self.output({'ok': True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
            else:
                self.send_error(404)

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    (root / 'port').write_text(str(server.server_port))
    def stop(*_):
        release.set()
        threading.Thread(target=server.shutdown, daemon=True).start()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(json.dumps({'port': server.server_port, 'stateDir': str(root)}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        revoked = None if state.get('credentialRevoked') else subprocess.run(command + ['revoke', '--base-dir', str(Path(args.base_dir).expanduser()), session['sessionId']],
            capture_output=True, text=True)
        if revoked is None or revoked.returncode == 0:
            secret.unlink(missing_ok=True)
            print('Read-only QA session revoked', flush=True)
        else:
            print('Session revocation failed; private session file retained for owner cleanup', flush=True)
            raise SystemExit(1)

if __name__ == '__main__':
    main()
