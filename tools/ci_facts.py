#!/usr/bin/env python3
"""CI helper: reduce `javap -c` output to a few facts PingGuard relies on
(numeric constants, field reads/writes, calls per method). Prints no full code."""
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
mode = sys.argv[2]

methods = re.split(r"\n(?=  \S.*\(.*\).*[;{]?\n)", text)


def facts(body):
    out = []
    for line in body.splitlines():
        m = re.search(r"\b(bipush|sipush|ldc|ldc_w|iconst_\w+|fconst_\w+)\s*(\S*)?.*?(//.*)?$", line)
        if m:
            out.append(m.group(1) + " " + (m.group(3) or m.group(2) or "").replace("// ", "").strip())
            continue
        m = re.search(r"\b(getfield|putfield)\b.*// Field (\S+)", line)
        if m:
            out.append(m.group(1) + " " + m.group(2).split(":")[0])
            continue
        m = re.search(r"\b(invoke\w+)\b.*// \w+ (\S+?)(:|$)", line)
        if m:
            out.append("call " + m.group(2))
            continue
        m = re.search(r"\b(if\w+|f2i|fdiv|fmul|fadd|fsub|iadd|isub|idiv|imul)\b", line)
        if m:
            out.append(m.group(1))
    return out


for body in methods:
    head = body.strip().splitlines()[0] if body.strip() else ""
    if mode == "gui":
        if "itle" not in body:
            continue
        if not re.search(r"(setTimes|Title|setTitle|resetTitle|clearTitles|tick)\(", head):
            continue
    elif mode == "bitmap":
        if not re.search(r"(advance|Glyph|load|bake|getActualGlyphWidth|width)", body, re.I):
            continue
    print("--- " + head.strip())
    seq = facts(body)
    # collapse runs to keep it short
    print("    " + " | ".join(seq[:160]))
