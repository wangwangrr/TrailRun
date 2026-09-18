#!/usr/bin/env python3
"""粗略但有用的 Kotlin 语法自检：括号配对（跳过字符串/字符/注释）。"""
import sys, pathlib

OPEN = {'(' : ')', '{' : '}', '[' : ']'}
CLOSE = {v: k for k, v in OPEN.items()}

def check(path: pathlib.Path):
    src = path.read_text(encoding='utf-8')
    stack = []
    i = 0
    n = len(src)
    line = 1
    problems = []
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        # 行注释
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j == -1 else j
            continue
        # 块注释（Kotlin 支持嵌套）
        if src.startswith('/*', i):
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith('/*', i):
                    depth += 1; i += 2
                elif src.startswith('*/', i):
                    depth -= 1; i += 2
                else:
                    if src[i] == '\n': line += 1
                    i += 1
            continue
        # 三引号字符串
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == '\n': line += 1
                i += 1
            i += 3
            continue
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\': i += 1
                elif src[i] == '\n':
                    line += 1
                    i += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\': i += 1
                i += 1
            i += 1
            continue
        if c in OPEN:
            stack.append((c, line))
        elif c in CLOSE:
            if not stack:
                problems.append(f"第 {line} 行多余的 '{c}'")
            else:
                op, ol = stack.pop()
                if OPEN[op] != c:
                    problems.append(f"第 {line} 行 '{c}' 与第 {ol} 行的 '{op}' 不匹配")
        i += 1
    for op, ol in stack:
        problems.append(f"第 {ol} 行的 '{op}' 未闭合")
    return problems

def main(root):
    root = pathlib.Path(root)
    bad = 0
    files = sorted(root.rglob('*.kt'))
    for f in files:
        p = check(f)
        if p:
            bad += 1
            print(f"[FAIL] {f}")
            for line in p[:10]:
                print("   ", line)
    print(f"--- 检查 {len(files)} 个 .kt 文件，{bad} 个存在括号问题 ---")
    return 1 if bad else 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else '.'))
