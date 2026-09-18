#!/usr/bin/env python3
"""报告每个 .kt 文件中未被使用的简单导入（仅提示，用于发现漏改的残留引用）。"""
import pathlib
import re
import sys

base = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r'D:\dsh\TrailRun\app\src\main\java')

imp = re.compile(r'^import\s+([A-Za-z0-9_.]+)(?:\s+as\s+(\w+))?\s*$')

for f in sorted(base.rglob('*.kt')):
    text = f.read_text(encoding='utf-8')
    lines = text.splitlines()
    imports = []
    for i, line in enumerate(lines):
        m = imp.match(line.strip())
        if m:
            full = m.group(1)
            alias = m.group(2)
            simple = alias or full.split('.')[-1]
            imports.append((simple, full, i + 1))
    # 去掉 import 行后统计使用
    body = '\n'.join(l for l in lines if not l.strip().startswith('import '))
    unused = []
    for simple, full, ln in imports:
        if simple == '*':
            continue
        # 简单单词边界匹配
        if not re.search(r'(?<![A-Za-z0-9_.])' + re.escape(simple) + r'(?![A-Za-z0-9_])', body):
            unused.append(f'{ln}: {full}')
    if unused:
        print(f'--- {f.relative_to(base)}')
        for u in unused:
            print('    ', u)
