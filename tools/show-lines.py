#!/usr/bin/env python3
"""打印文件指定行范围内的内容，用于分段阅读大文件。"""
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
ranges = sys.argv[2:]
lines = path.read_text(encoding='utf-8').split('\n')
print(f'=== {path.name}  共 {len(lines)} 行 ===')
for r in ranges:
    if '-' in r:
        a, b = r.split('-')
        a, b = int(a), int(b)
    else:
        a = b = int(r)
    print(f'--- 行 {a}..{b} ---')
    for i in range(max(1, a), min(len(lines), b) + 1):
        print(f'{i:5}: {lines[i-1]}')
