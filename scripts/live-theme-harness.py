#!/usr/bin/env python3
"""Private, bounded desktop-theme QA harness. Credentials never enter argv, logs or the repository."""
import argparse
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ssh-host', required=True)
    parser.add_argument('--t3-bin', required=True)
    parser.add_argument('--base-dir', required=True)
    parser.add_argument('--remote-port', type=int, required=True)
    parser.add_argument('--forward-port', type=int, required=True)
    parser.add_argument('--expected-theme', required=True)
    parser.add_argument('--state-dir')
    args = parser.parse_args()
    root = Path(args.state_dir or tempfile.mkdtemp(prefix='ot3-theme-qa-')).resolve()
    if root.is_relative_to(Path(__file__).resolve().parents[1]) or (root / 'session.json').exists():
        parser.error('Use a fresh private state directory outside the repository')
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    root.chmod(0o700)
    def remote(arguments):
        return subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=5', args.ssh_host,
            shlex.join(arguments)], capture_output=True, text=True, timeout=30)
    command = [args.t3_bin, 'auth', 'session']
    issued = remote(command + ['issue', '--base-dir', args.base_dir, '--scope', 'orchestration:read',
        '--scope', 'filesystem:read', '--ttl', '10m', '--label', 'ot3-emulator-theme-readonly', '--json'])
    if issued.returncode:
        raise RuntimeError('Temporary session issue failed; credential output withheld')
    session = json.loads(issued.stdout)
    secret = root / 'session.json'
    with os.fdopen(os.open(secret, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        json.dump(session, output)
    tunnel = None
    try:
        tunnel = subprocess.Popen(['ssh', '-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
            '-L', f'127.0.0.1:{args.forward_port}:127.0.0.1:{args.remote_port}', args.ssh_host],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            if tunnel.poll() is not None:
                raise RuntimeError('Private forwarding failed')
            try:
                with socket.create_connection(('127.0.0.1', args.forward_port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError('Private forward not ready')
        release = threading.Event()
        state = {'ready': False}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def output(self, value):
                data = json.dumps(value).encode()
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.end_headers(); self.wfile.write(data)
            def do_GET(self):
                if self.path == '/config':
                    self.output({'url': f'http://10.0.2.2:{args.forward_port}', 'token': session['token'],
                        'expectedTheme': args.expected_theme})
                elif self.path == '/continue':
                    release.wait(210); self.output({'released': release.is_set()})
                elif self.path == '/status':
                    self.output(state)
                else:
                    self.send_error(404)
            def do_POST(self):
                if self.path == '/ready':
                    state.update(json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0')))))
                    state['ready'] = True
                    (root / 'verification.json').write_text(json.dumps(state, indent=2) + '\n')
                    self.output({'ok': True})
                elif self.path == '/release':
                    release.set(); self.output({'ok': True})
                elif self.path == '/finish':
                    self.output({'ok': True}); threading.Thread(target=server.shutdown, daemon=True).start()
                else:
                    self.send_error(404)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        def stop(*_):
            release.set(); threading.Thread(target=server.shutdown, daemon=True).start()
        signal.signal(signal.SIGTERM, stop)
        (root / 'port').write_text(str(server.server_port))
        print(json.dumps({'port': server.server_port, 'stateDir': str(root)}), flush=True)
        server.serve_forever()
        server.server_close()
    finally:
        if tunnel:
            tunnel.terminate()
            try: tunnel.wait(timeout=5)
            except subprocess.TimeoutExpired: tunnel.kill(); tunnel.wait()
        revoked = remote(command + ['revoke', '--base-dir', args.base_dir, session['sessionId']])
        (root / 'cleanup.json').write_text(json.dumps({'sessionRevoked': revoked.returncode == 0, 'forwardClosed': True}))
        if revoked.returncode == 0:
            secret.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
