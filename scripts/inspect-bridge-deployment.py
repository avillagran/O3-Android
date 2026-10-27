#!/usr/bin/env python3
"""Read-only, bounded catalog preflight; never emits credentials or route URLs."""
import argparse
import json
import inspect as introspection
from pathlib import Path
import re
import subprocess
import sys


def inspect(config):
    import datetime
    import os

    def home_path(value):
        return Path(value).expanduser()

    desktop = home_path(config['desktop_client'])
    result = {'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'readOnly': True, 'desktop': {}, 'bridges': [],
              'publisherProcesses': 0}
    try:
        run = subprocess.run([str(desktop), 'connections-view'], capture_output=True,
                             timeout=15, check=True)
        if len(run.stdout) > 1024 * 1024:
            raise ValueError('oversize')
        view = json.loads(run.stdout)
        rows = view['environments']
        if not isinstance(rows, list) or len(rows) > 128:
            raise ValueError('shape')
        result['desktop'] = {'available': True, 'localEnabled': view.get('localEnabled') is True,
            'environments': [{'id': row.get('id'), 'name': row.get('name'),
                'enabled': row.get('enabled') is True,
                'routes': [{'kind': route.get('kind'),
                            'urlPresent': isinstance(route.get('url'), str),
                            'sshConfigured': bool(route.get('ssh'))}
                           for route in row.get('routes', [])]}
                for row in rows]}
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError):
        result['desktop'] = {'available': False}
    for directory in config['state_dirs']:
        root = home_path(directory)
        result['bridges'].append({'stateDirectory': directory,
            'present': root.is_dir(), 'runtimeSocketPresent': (root / 'control.sock').is_socket(),
            'catalogPublicationPresent': (root / 'client-catalog.json').is_file()})
    # Only fixed process metadata is returned, never full command lines or environments.
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            argv = (entry / 'cmdline').read_bytes().split(b'\0')
            if argv and os.path.basename(os.fsdecode(argv[0])) == 'omabridge' and b'catalog-publish' in argv[1:]:
                result['publisherProcesses'] += 1
        except OSError:
            pass
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ssh', help='Existing authorized SSH user@host; no configuration is changed')
    parser.add_argument('--desktop-client', required=True)
    parser.add_argument('--state-dir', action='append', required=True)
    args = parser.parse_args()
    config = {'desktop_client': args.desktop_client, 'state_dirs': args.state_dir}
    if args.ssh:
        if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@:-]*', args.ssh):
            parser.error('invalid SSH target')
        # Send configuration as Python data on stdin; no paths enter the remote shell command.
        source = 'import json, subprocess\nfrom pathlib import Path\n'
        source += introspection.getsource(inspect)
        source += '\nprint(json.dumps(inspect(' + repr(config) + '), ensure_ascii=False))\n'
        try:
            run = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8',
                                  args.ssh, 'python3 -'], input=source, text=True,
                                 capture_output=True, timeout=30, check=True)
            result = json.loads(run.stdout)
        except (OSError, subprocess.SubprocessError, ValueError):
            print(json.dumps({'readOnly': True, 'remoteAvailable': False}))
            return 1
    else:
        result = inspect(config)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['desktop']['available'] else 1


if __name__ == '__main__':
    sys.exit(main())
