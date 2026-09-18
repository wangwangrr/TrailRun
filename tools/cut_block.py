#!/usr/bin/env python3
"""从指定行的一个孤立 '{' 开始，按括号配对找到配对的 '}' 并整段删除。"""
import sys
import pathlib

OPEN = {'(': ')', '{': '}', '[': ']'}


def scan(src):
    i, n, line = 0, len(src), 1
    out = []
    while i < n:
        c = src[i]
        if c == '\n':
            out.append((i, '\n', line)); line += 1; i += 1; continue
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j == -1 else j; continue
        if src.startswith('/*', i):
            d = 1; i += 2
            while i < n and d:
                if src.startswith('/*', i): d += 1; i += 2
                elif src.startswith('*/', i): d -= 1; i += 2
                else:
                    if src[i] == '\n': line += 1
                    i += 1
            continue
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == '\n': line += 1
                i += 1
            i += 3; continue
        if c in ('"', "'"):
            q = c; i += 1
            while i < n and src[i] != q:
                if src[i] == '\\': i += 1
                elif src[i] == '\n': line += 1
                i += 1
            i += 1; continue
        out.append((i, c, line)); i += 1
    return out


def main():
    path = pathlib.Path(sys.argv[1])
    start_line = int(sys.argv[2])
    src = path.read_text(encoding='utf-8')
    lines = src.split('\n')
    start_off = sum(len(l) + 1 for l in lines[:start_line - 1])

    toks = [t for t in scan(src) if t[0] >= start_off]
    # 跳过换行/空白 token，找第一个有意义的字符
    toks = [t for t in toks if not t[1].isspace()]
    if not toks or toks[0][1] != '{':
        print('第 %d 行不是以 { 开头：%r' % (start_line, toks[0][1] if toks else 'EOF'))
        return 1

    depth = 0
    end_off = None
    for idx, c, line in toks:
        if c in OPEN:
            depth += 1
        elif c in (')', '}', ']'):
            depth -= 1
            if depth == 0:
                end_off = idx + 1
                break
    if end_off is None:
        print('括号未闭合')
        return 1

    end_line = src[:end_off].count('\n') + 1
    new = src[:start_off].rstrip('\n') + '\n' + src[end_off:].lstrip('\n')
    path.write_text(new, encoding='utf-8')
    print(f'已删除第 {start_line}-{end_line} 行（{end_line - start_line + 1} 行）')
    print(f'文件行数 {src.count(chr(10)) + 1} -> {new.count(chr(10)) + 1}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
