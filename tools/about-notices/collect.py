#!/usr/bin/env python3
"""Rebuild bundled notices from the resolved release graph and cached upstream archives.
Run the companion Gradle init script first. No network or generated string edits.
"""
from pathlib import Path
import io
import json
import zipfile
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'app/src/main/assets/licenses'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'


def notices(data):
    with zipfile.ZipFile(data) as archive:
        result = []
        for name in sorted(archive.namelist()):
            if name.endswith('/'):
                continue
            if name.upper().startswith('META-INF/') and any(s in name.upper() for s in ('LICENSE', 'NOTICE', 'COPYRIGHT')):
                result.append(archive.read(name).decode('utf-8'))
            elif name == 'classes.jar':
                result.extend(notices(io.BytesIO(archive.read(name))))
        return result


def pom_licenses(directory, depth=0):
    if depth > 8:
        raise ValueError('POM parent cycle')
    paths = sorted(directory.glob('*/*.pom'))
    if not paths:
        return []
    root = ET.parse(paths[0]).getroot()
    # Some old POMs have no namespace.
    for node in root.iter():
        node.tag = node.tag.split('}')[-1]
    licenses = [(x.findtext('name', ''), x.findtext('url', '')) for x in root.findall('licenses/license')]
    if licenses:
        return licenses
    parent = root.find('parent')
    if parent is not None:
        return pom_licenses(CACHE / parent.findtext('groupId') / parent.findtext('artifactId') / parent.findtext('version'), depth + 1)
    return []


def main():
    entries = json.loads((ROOT / 'tools/about-notices/custom.json').read_text())
    old = {p['file'] for p in json.loads((ASSETS / 'index.json').read_text())[8:]}
    provenance = []
    missing = []
    for component in sorted(json.loads((ROOT / 'build/license-artifacts.json').read_text()), key=lambda c: (c['group'], c['name'])):
        group, name, version = (component[k] for k in ('group', 'name', 'version'))
        directory = CACHE / group / name / version
        binaries = sorted(p for p in directory.glob('*/*') if p.suffix in ('.aar', '.jar') and not any(s in p.name for s in ('-sources', '-javadoc')))
        if not binaries:  # BOMs / metadata-only platform components have no bundled code.
            continue
        archive = binaries[0]
        texts = notices(archive)
        method = "embedded"
        if not texts:
            method = "maven-pom"
            licenses = pom_licenses(directory)
            if not licenses or not all('apache' in (name + url).lower() for name, url in licenses):
                missing.append(f'{group}:{name}:{version}')
                continue
            texts = [f'{group}:{name}:{version}\nLicense declared in upstream Maven POM: ' + ', '.join(n + ' (' + u + ')' for n, u in licenses),
                     (ASSETS / 'Apache-2.0.txt').read_text()]

        text = '\n\n'.join(dict.fromkeys(texts))
        filename = f'{group}.{name}-{version}.txt'
        (ASSETS / filename).write_text(text)
        old.discard(filename)
        entries.append(dict(name=f'{group}:{name}', version=version, license='Apache-2.0' if 'Apache License' in text else 'Notice', file=filename,
                            source=f'https://maven.google.com/web/index.html#{group}:{name}:{version}' if group.startswith('androidx') else f'https://repo1.maven.org/maven2/{group.replace(".", "/")}/{name}/{version}/'))
        provenance.append(dict(coordinate=f'{group}:{name}:{version}', archive=archive.name, sha256=hashlib.sha256(archive.read_bytes()).hexdigest(), notice=filename, method=method))
    if missing:
        raise ValueError(f'Missing licenses: {missing}')
    for file in old:
        (ASSETS / file).unlink(missing_ok=True)
    (ASSETS / 'index.json').write_text(json.dumps(entries, ensure_ascii=False, indent=2) + '\n')
    (ROOT / 'tools/about-notices/provenance.json').write_text(json.dumps(dict(artifacts=provenance, withoutEmbeddedNotice=missing), indent=2) + '\n')
    print(f'{len(entries)} notices; {len(missing)} unresolved licenses')


if __name__ == '__main__':
    main()
