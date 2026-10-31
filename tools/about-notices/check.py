#!/usr/bin/env python3
"""Check hand-kept About resource parity, format arguments and packaged notices."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
res = root / 'app/src/main/res'
files = sorted(res.glob('values*/strings_about.xml'))
assert len(files) == 20, f'Expected 20 locales, got {len(files)}'
base = {e.attrib['name']: e.text or '' for e in ET.parse(res / 'values/strings_about.xml').getroot()}
for file in files:
    nodes = list(ET.parse(file).getroot())
    values = {e.attrib['name']: e.text or '' for e in nodes}
    assert len(values) == len(nodes), f'Duplicate key: {file}'
    assert values.keys() == base.keys(), f'Key parity: {file}'
    for key, text in values.items():
        assert sorted(re.findall(r'%\d+\$[ds]', text)) == sorted(re.findall(r'%\d+\$[ds]', base[key])), (file, key)
        assert text.strip() and not any(p in text for p in ('TODO', 'FIXME', 'NotYet', 'PLACEHOLDER')), (file, key)
assets = root / 'app/src/main/assets/licenses'
entries = json.loads((assets / 'index.json').read_text())
assert len(entries) == len({e['file'] for e in entries})
for entry in entries:
    assert '/' not in entry['file'] and '..' not in entry['file']
    assert entry['source'].startswith('https://')
    assert (assets / entry['file']).read_text().strip(), entry
print(f'{len(base)} keys × {len(files)} locales; argument parity; {len(entries)} packaged notices: PASS')
