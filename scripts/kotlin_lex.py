"""Just enough Kotlin lexing for the source checks, shared by them.

`check_activity_scope_work.py`, `check_swallowed_cancellation.py` and
`check_kotlin_file_size.py` read Kotlin source with these helpers: blank its
comments and literals, match brackets, find functions, and read an allowlist.
Each check puts this directory on `sys.path` before it imports this module, so
it loads both as `python scripts/<check>.py` (even under `-I`) and from
`scripts/tests`. Stdlib only.
"""
from __future__ import annotations

import re
from collections.abc import Iterator
from pathlib import Path
from typing import NamedTuple

#: The TD- or ADR- pointer every allowlist entry's reason must name.
POINTER = re.compile(r"\b(?:TD|ADR)-\d+\b")


def strip_code(text: str) -> str:
    """Blank comments, string and char literals; every offset and newline stays.

    Only brackets in code are matched against each other, so a `"{"` in a
    string or a `}` in a comment must not count.
    """
    out = list(text)
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
        elif text.startswith("/*", i):
            depth, end = 1, i + 2
            while end < n and depth:
                if text.startswith("/*", end):
                    depth, end = depth + 1, end + 2
                elif text.startswith("*/", end):
                    depth, end = depth - 1, end + 2
                else:
                    end += 1
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            while end < n and text[end] == '"':
                end += 1
        elif text[i] == '"':
            end = _string_end(text, i + 1)
        elif text[i] == "'":
            end = i + 1
            if end < n and text[end] == "\\":
                end += 2
            close = text.find("'", end)
            end = n if close < 0 else close + 1
        else:
            i += 1
            continue
        blank(i, end)
        i = end
    return "".join(out)


def _string_end(text: str, j: int) -> int:
    """The offset just past a one-line string literal opened before `j`."""
    n = len(text)
    while j < n:
        c = text[j]
        if c == "\\":
            j += 2
        elif text.startswith("${", j):
            depth, j = 1, j + 2
            while j < n and depth:
                depth += {"{": 1, "}": -1}.get(text[j], 0)
                j += 1
        elif c == '"':
            return j + 1
        elif c == "\n":
            return j
        else:
            j += 1
    return n


OPEN = {"(": ")", "{": "}", "[": "]"}
CLOSE = {v: k for k, v in OPEN.items()}


def matching(code: str, start: int) -> int:
    """Offset of the bracket closing the one at `start`, or -1.

    Every bracket kind counts toward the depth, not only the one at `start`.
    """
    depth = 0
    for k in range(start, len(code)):
        if code[k] in OPEN:
            depth += 1
        elif code[k] in CLOSE:
            depth -= 1
            if depth == 0:
                return k
    return -1


def skip_space(code: str, pos: int) -> int:
    while pos < len(code) and code[pos].isspace():
        pos += 1
    return pos


def enclosing_paren(code: str, pos: int) -> int:
    """The `(` whose argument list holds `pos`, or -1 when `pos` is not in one."""
    depth = 0
    for k in range(pos - 1, -1, -1):
        c = code[k]
        if c in CLOSE:
            depth += 1
        elif c in OPEN:
            if depth == 0:
                return k if c == "(" else -1
            depth -= 1
    return -1


def line_of(code: str, pos: int) -> int:
    return code.count("\n", 0, pos) + 1


_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:([\w.<>?, ]+)\.)?(\w+)\s*\(")
_PROPERTY = re.compile(r"\b(?:val|var)\s+(\w+)")


class Function(NamedTuple):
    name: str
    start: int
    params: tuple[int, int]  # the parameter list's `(` and `)`
    body: int  # offset of the body's first character
    end: int
    receiver: str  # an extension's receiver type, else ""


def _expression_end(code: str, pos: int) -> int:
    """Where an expression body that starts at `pos` ends: the first line end at
    bracket depth 0 that the next line does not continue (`.let`, `?:`, `&&`)."""
    depth, end = 0, skip_space(code, pos)
    while end < len(code):
        c = code[end]
        if c == "\n" and depth == 0:
            following = skip_space(code, end)
            if following >= len(code) or code[following] not in ".?:&|+-*/":
                return end
        depth += 1 if c in OPEN else -1 if c in CLOSE else 0
        end += 1
    return end


def functions(code: str) -> list[Function]:
    """Every `fun` with a body, block (`{ }`) or expression (`= …`)."""
    found = []
    for match in _FUN.finditer(code):
        close = matching(code, match.end() - 1)
        if close < 0:
            continue
        brace, equals = code.find("{", close), code.find("=", close)
        if equals >= 0 and (brace < 0 or equals < brace):
            end = _expression_end(code, equals + 1)
            body = equals + 1
        elif brace >= 0:
            body, end = brace + 1, matching(code, brace)
            if end < 0:
                continue
        else:
            continue
        receiver = (match.group(1) or "").strip().split("<")[0].split(".")[-1]
        found.append(
            Function(match.group(2), match.start(), (match.end() - 1, close), body, end, receiver)
        )
    return found


def enclosing(code: str, funs: list[Function], pos: int) -> str:
    """The function holding `pos`; outside one, the property it initialises."""
    inside = [f for f in funs if f.start <= pos <= f.end]
    if inside:
        return max(inside, key=lambda f: f.start).name
    properties = list(_PROPERTY.finditer(code, 0, pos))
    return properties[-1].group(1) if properties else "<init>"


def allowlist_lines(path: Path) -> Iterator[tuple[int, str]]:
    """(line number, stripped text) of each entry in an allowlist file: blank
    lines and `#` comments are skipped, and a missing file has none."""
    if not path.is_file():
        return
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if line and not line.startswith("#"):
            yield number, line
