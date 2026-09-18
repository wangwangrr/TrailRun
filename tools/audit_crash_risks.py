#!/usr/bin/env python3
"""扫描可能在启动/协程中抛出未捕获异常的位置。

真机闪退 = 主线程未捕获异常。以下模式会导致它：
  1. viewModelScope.launch / lifecycleScope.launch 里未包 try 的调用
  2. ViewModel init / Application.onCreate / Activity.onCreate 里的未保护调用
  3. catch 列表过窄（只捕特定类型，别的 RuntimeException 会漏出去）
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r'D:\dsh\TrailRun\app\src\main\java')

launch_re = re.compile(r'(viewModelScope|lifecycleScope|CoroutineScope\([^)]*\))\.launch')
init_re = re.compile(r'^\s*init\s*\{')
override_re = re.compile(r'override fun (onCreate|onStartCommand|onDestroy)\s*\(')
catch_re = re.compile(r'catch\s*\(\s*\w+\s*:\s*(\w+)\s*\)')

print('=== 1) 协程入口（未捕获异常会崩主线程）===')
for f in sorted(root.rglob('*.kt')):
    lines = f.read_text(encoding='utf-8').split('\n')
    for i, line in enumerate(lines):
        if launch_re.search(line):
            # 往后看 25 行里有没有 try / runCatching
            window = '\n'.join(lines[i:i + 25])
            guarded = ('try {' in window) or ('runCatching' in window) or ('try\n' in window)
            mark = 'OK ' if guarded else '!! '
            print(f'  {mark}{f.name}:{i+1}  {line.strip()[:88]}')
            if not guarded:
                for j in range(i, min(i + 8, len(lines))):
                    s = lines[j].strip()
                    if s and not launch_re.search(lines[j]):
                        print(f'          +{j+1-i}: {s[:84]}')

print('\n=== 2) init 块与生命周期入口 ===')
for f in sorted(root.rglob('*.kt')):
    lines = f.read_text(encoding='utf-8').split('\n')
    for i, line in enumerate(lines):
        if init_re.match(line) or override_re.search(line):
            window = '\n'.join(lines[i:i + 22])
            guarded = 'try {' in window or 'runCatching' in window
            mark = 'OK ' if guarded else '?? '
            print(f'  {mark}{f.name}:{i+1}  {line.strip()[:80]}')

print('\n=== 3) catch 列表过窄（只捕具名异常）===')
for f in sorted(root.rglob('*.kt')):
    text = f.read_text(encoding='utf-8')
    lines = text.split('\n')
    for i, line in enumerate(lines):
        m = catch_re.search(line)
        if m and m.group(1) not in ('Throwable', 'Exception'):
            # 看这个 try 块里有没有兜底 catch
            window = '\n'.join(lines[max(0, i - 12):i + 8])
            has_broad = re.search(r'catch\s*\(\s*\w+\s*:\s*(Throwable|Exception)\s*\)', window)
            if not has_broad:
                print(f'  !! {f.name}:{i+1}  {line.strip()[:80]}')
                print(f'          (该 try 块没有 Throwable/Exception 兜底)')
