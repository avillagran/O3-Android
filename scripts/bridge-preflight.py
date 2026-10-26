#!/usr/bin/env python3
"""Read-only deployment probe. Never reads credentials or changes bridge/T3 state."""
import argparse
import datetime
import json
import re
import shlex
import subprocess
import urllib.error
import urllib.parse
import urllib.request


def origin(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in ('', '/')):
        raise argparse.ArgumentTypeError('Expected a credential-free HTTP(S) origin')
    return value.rstrip('/')


def remote(host, argv):
    """Fixed local commands; remote output is never printed or used as a command."""
    try:
        result = subprocess.run(
            ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=8', host,
             shlex.join(argv)], capture_output=True, timeout=25, check=False)
        if result.returncode or len(result.stdout) > 1024 * 1024:
            return None
        return result.stdout.decode('utf-8')
    except (OSError, UnicodeError, subprocess.TimeoutExpired):
        return None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def descriptor_matches(base, expected):
    try:
        # No proxy or redirect can silently select a different route/authority.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(base + '/.well-known/t3/environment', timeout=10) as response:
            payload = response.read(65537)
        if len(payload) > 65536:
            return {'available': False, 'matches': None}
        data = json.loads(payload)
        return {'available': True, 'matches': data.get('environmentId') == expected}
    except (OSError, ValueError, TypeError, AttributeError):
        return {'available': False, 'matches': None}


def sanitized_catalog(raw, expected):
    """Only report the requested UUID; do not export routes, labels or other rows."""
    try:
        view = json.loads(raw)
        rows = view['environments']
        if not isinstance(rows, list):
            raise ValueError()
        matches = [row for row in rows if isinstance(row, dict) and row.get('id') == expected]
        if len(matches) != 1:
            return {'available': True, 'present': bool(matches), 'unique': len(matches) <= 1}
        row = matches[0]
        return {'available': True, 'present': True, 'unique': True,
                'enabled': row.get('enabled') is True,
                'routeCount': len(row['routes']) if isinstance(row.get('routes'), list) else None}
    except (ValueError, TypeError, KeyError):
        return {'available': False, 'present': None}


def probe(args):
    catalog = sanitized_catalog(remote(args.desktop_host, [args.desktop_client, 'connections-view']), args.environment)
    help_text = remote(args.desktop_host, [args.bridge_binary, '--help'])
    version_text = remote(args.desktop_host, [args.bridge_binary, '--version'])
    version = re.search(r'\bomabridge ([0-9]+\.[0-9]+\.[0-9]+)\b', version_text or '')
    return {
        'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'readOnly': True,
        'environmentId': args.environment,
        'desktopCatalog': catalog,
        'installedBridge': {
            'available': help_text is not None,
            'version': version.group(1) if version else None,
            'catalogPublish': bool(re.search(r'^\s+catalog-publish\s', help_text or '', re.M)) if help_text is not None else None,
        },
        'nodeDescriptor': descriptor_matches(args.node_url, args.environment),
        'portableDescriptor': descriptor_matches(args.portable_url, args.environment),
        'authorization': 'not_tested',
        'androidDiscovery': 'not_tested',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desktop-host', required=True)
    parser.add_argument('--desktop-client', required=True)
    parser.add_argument('--bridge-binary', required=True)
    parser.add_argument('--environment', required=True)
    parser.add_argument('--node-url', type=origin, required=True)
    parser.add_argument('--portable-url', type=origin, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@:-]*', args.desktop_host):
        parser.error('Invalid SSH host')
    if not all(path.startswith('/') and '\n' not in path for path in (args.desktop_client, args.bridge_binary)):
        parser.error('Executables must be fixed absolute paths')
    if not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', args.environment):
        parser.error('Environment must be a canonical UUID')
    if urllib.parse.urlsplit(args.portable_url).scheme != 'https':
        parser.error('Portable route must use HTTPS')
    print(json.dumps(probe(args), indent=2))


if __name__ == '__main__':
    main()
