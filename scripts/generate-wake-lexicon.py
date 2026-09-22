"""Rebuild bundled Chinese phonemes with pypinyin==0.55.0 (install in a temporary venv)."""
from pathlib import Path
import hashlib
import json
import re
from pypinyin.constants import PINYIN_DICT

assets = Path(__file__).resolve().parent.parent / 'app/src/main/assets/sherpa-kws'
tokens = {line.split()[0] for line in (assets / 'tokens.txt').read_text().splitlines()}
entries = []
for codepoint, readings in sorted(PINYIN_DICT.items()):
    variants = []
    for reading in readings.split(','):
        initial = re.match(r'^(zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])', reading)
        phones = [initial[0], reading[len(initial[0]):]] if initial else [reading]
        if all(phone in tokens for phone in phones):
            candidate = ' '.join(phones)
            if candidate not in variants:
                variants.append(candidate)
    if variants:
        entries.append(chr(codepoint) + '\t' + '|'.join(variants))
(assets / 'zh.phone').write_text('\n'.join(entries) + '\n')
source = json.loads((assets / 'SOURCE.json').read_text())
source['chinese_lexicon'] = {'source': 'https://github.com/mozillazg/python-pinyin/tree/v0.55.0', 'generator': 'scripts/generate-wake-lexicon.py', 'license': 'PINYIN_LICENSE.txt'}
for name in ['zh.phone', 'PINYIN_LICENSE.txt']:
    source['files'][name] = hashlib.sha256((assets / name).read_bytes()).hexdigest()
(assets / 'SOURCE.json').write_text(json.dumps(source, indent=2) + '\n')
print(f'Wrote {len(entries)} Chinese characters')
