#!/usr/bin/env python3
"""交叉检查 Android 资源引用是否都已定义（R.xxx / @xxx）。"""
import pathlib
import re
import sys

base = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r'D:\dsh\TrailRun\app\src\main')
res = base / 'res'
java = base / 'java'

defined = set()
for f in res.rglob('*'):
    if not f.is_file() or f.suffix != '.xml':
        continue
    parent = f.parent.name
    name = f.stem
    if parent.startswith('drawable'):
        defined.add(('drawable', name))
    elif parent.startswith('mipmap'):
        defined.add(('mipmap', name))
    elif parent.startswith('values'):
        txt = f.read_text(encoding='utf-8')
        pattern = r'<(string|style|color|dimen|integer|bool)\s+name="([^"]+)"'
        for m in re.finditer(pattern, txt):
            defined.add((m.group(1), m.group(2)))

print('已定义资源条目:', len(defined))

missing = []
ref_kotlin = re.compile(r'R\.(drawable|string|mipmap|style|color)\.([A-Za-z0-9_]+)')
for f in java.rglob('*.kt'):
    txt = f.read_text(encoding='utf-8')
    for m in ref_kotlin.finditer(txt):
        kind, name = m.group(1), m.group(2)
        if (kind, name) not in defined:
            missing.append((str(f.relative_to(base)), kind, name))

ref_xml = re.compile(r'@(drawable|mipmap|style|string|color)/([A-Za-z0-9_]+)')
targets = [base / 'AndroidManifest.xml'] + sorted(res.rglob('*.xml'))
for f in targets:
    txt = f.read_text(encoding='utf-8')
    for m in ref_xml.finditer(txt):
        kind, name = m.group(1), m.group(2)
        # 系统资源以 @android: 前缀出现，上面的正则不会匹配到，这里无需特判
        if (kind, name) not in defined:
            missing.append((str(f.relative_to(base)), kind, name))

if missing:
    print('缺失引用:')
    for item in sorted(set(missing)):
        print('  ', item)
    sys.exit(1)
print('全部资源引用均可解析 OK')
