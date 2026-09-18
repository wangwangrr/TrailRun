#!/usr/bin/env python3
"""校验 vector drawable 的结构：gradient 是否都有 aapt:attr 包裹、path 是否有填充来源。"""
import pathlib
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
AAPT = '{http://schemas.android.com/aapt}'

path = pathlib.Path(sys.argv[1])
tree = ET.parse(path)
root = tree.getroot()
print('根元素:', root.tag.split('}')[-1])
print('viewport:', root.get(A + 'viewportWidth'), 'x', root.get(A + 'viewportHeight'))

paths = [e for e in root if e.tag.endswith('path')]
print('path 数量:', len(paths))

problems = []
for i, e in enumerate(paths):
    fill = e.get(A + 'fillColor')
    stroke = e.get(A + 'strokeColor')
    attr_children = [c for c in e if c.tag.startswith(AAPT)]
    grad = None
    for c in attr_children:
        if c.get('name') == 'android:fillColor':
            gs = [g for g in c if g.tag.endswith('gradient')]
            if gs:
                grad = gs[0]
    ok = bool(fill) or bool(stroke) or grad is not None
    if not ok:
        problems.append(i)
    kind = 'gradient' if grad else ('纯色' if fill else ('描边' if stroke else '缺失!'))
    items = len([g for g in (grad or []) if g.tag.endswith('item')])
    extra = (' items=' + str(items)) if grad is not None else ''
    print('  #%d %s%s' % (i, kind, extra))

if problems:
    print('存在没有填充/描边的 path:', problems)
    sys.exit(1)
print('全部 path 都有填充来源 OK')
