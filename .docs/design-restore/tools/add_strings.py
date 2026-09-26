#!/usr/bin/env python3
"""追加 movo_* 文案到三份 strings_movo.xml。用法：add_strings.py key '英文' '简体' '繁体' [key ...]"""
import sys, os, html
root = os.path.join(os.path.dirname(__file__), '../../../app/src/main/res')
args = sys.argv[1:]
assert len(args) % 4 == 0, 'need key en hans hant groups'
for folder, idx in (('values', 1), ('values-b+zh+Hans', 2), ('values-b+zh+Hant', 3)):
    path = os.path.join(root, folder, 'strings_movo.xml')
    s = open(path).read()
    add = ''
    for i in range(0, len(args), 4):
        key = args[i]
        if f'name="{key}"' in s:
            continue
        val = html.escape(args[i + idx], quote=False).replace("'", "\\'")
        add += f'  <string name="{key}">{val}</string>\n'
    s = s.replace('</resources>', add + '</resources>')
    open(path, 'w').write(s)
print('ok')
