#!/usr/bin/env python3
"""按「括号深度」精确切除一个 Kotlin 顶层函数，再插入替换内容。

行号法不可靠（内层 lambda 的 } 与函数结尾同列，会提前截断），
这里做真正的词法扫描：跳过字符串/字符/注释，跟踪 { } ( ) [ ] 的深度，
找到深度归零的那个 } 才算函数结束。

用法:
  python tools/cut_function.py <文件> <函数名> <替换片段文件|null>
"""
import sys
import pathlib

OPEN = {'(': ')', '{': '}', '[': ']'}


def scan(src):
    """产出 (index, char, line) 并跳过字符串/注释内容。"""
    i, n, line = 0, len(src), 1
    out = []
    while i < n:
        c = src[i]
        if c == '\n':
            out.append((i, '\n', line))
            line += 1
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j == -1 else j
            continue
        if src.startswith('/*', i):
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith('/*', i):
                    depth += 1; i += 2
                elif src.startswith('*/', i):
                    depth -= 1; i += 2
                else:
                    if src[i] == '\n':
                        line += 1
                    i += 1
            continue
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 3
            continue
        if c in ('"', "'"):
            quote = c
            i += 1
            while i < n and src[i] != quote:
                if src[i] == '\\':
                    i += 1
                elif src[i] == '\n':
                    line += 1
                i += 1
            i += 1
            continue
        out.append((i, c, line))
        i += 1
    return out


def find_function(src, needle):
    toks = scan(src)
    start_idx = None
    for idx, c, line in toks:
        if src.startswith(needle, idx):
            # 往前找行首，确保是声明开头而不是引用
            ls = src.rfind('\n', 0, idx) + 1
            prefix = src[ls:idx]
            if prefix.strip() == '' or prefix.strip().startswith('@'):
                start_idx = ls
                break
    if start_idx is None:
        return None, None

    # 从声明处开始按深度找结束花括号
    depth = 0
    seen_open = False
    end_idx = None
    for idx, c, line in toks:
        if idx < start_idx:
            continue
        if c in OPEN:
            depth += 1
            seen_open = True
        elif c in (')', '}', ']'):
            depth -= 1
            if seen_open and depth == 0:
                end_idx = idx + 1
                break
    return start_idx, end_idx


def main():
    path = pathlib.Path(sys.argv[1])
    needle = sys.argv[2]
    repl_file = sys.argv[3] if len(sys.argv) > 3 else None

    src = path.read_text(encoding='utf-8')
    start, end = find_function(src, needle)
    if start is None or end is None:
        print(f'未找到函数: {needle}')
        return 1

    before = src[:start].rstrip('\n')
    after = src[end:].lstrip('\n')
    repl = pathlib.Path(repl_file).read_text(encoding='utf-8').rstrip('\n') if repl_file and repl_file != 'null' else ''

    pieces = [before]
    if repl:
        pieces.append(repl)
    if after:
        pieces.append(after)
    new = '\n\n'.join(p for p in pieces if p) + '\n'

    path.write_text(new, encoding='utf-8')
    start_line = src[:start].count('\n') + 1
    end_line = src[:end].count('\n') + 1
    print(f'已切除 {needle}: 第 {start_line}-{end_line} 行 ({end_line - start_line + 1} 行)')
    print(f'文件行数 {src.count(chr(10)) + 1} -> {new.count(chr(10)) + 1}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
