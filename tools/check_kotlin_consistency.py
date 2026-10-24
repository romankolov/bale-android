#!/usr/bin/env python3
"""Heuristic Kotlin source consistency checker used by CI to catch cross-layer drift early.

Validates: balanced delimiters, cross-layer call arity against declared members,
repository overrides against the domain interfaces, and named-argument fields of domain
data classes. It is intentionally a static heuristic (no JVM needed) that complements, not
replaces, the real Kotlin compiler run in the `android` job.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = sorted(p for p in ROOT.rglob("*.kt") if "/build/" not in str(p) and "/src/generated/" not in str(p))

IGNORED_MEMBERS = {"copy", "let", "also", "apply", "build", "toBuilder", "newBuilder", "first", "map", "filter", "equals", "hashCode", "toString", "domain"}


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT))


def strip_noise(text: str) -> str:
    """Blank out string/char literals and comments so delimiter counting is reliable."""
    out = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == '"':
            quote = '"""' if text.startswith('"""', i) else '"'
            j = i + len(quote)
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text.startswith(quote, j):
                    j += len(quote)
                    break
                j += 1
            # Strings become 'x' filler (not spaces) so blanked interpolation still counts as an argument.
            out.append("x" * (j - i))
            i = j
            continue
        if ch == "'":
            j = i + 1
            while j < n and text[j] != "'":
                if text[j] == "\\":
                    j += 1
                j += 1
            out.append(" " * (j - i + 1))
            i = j + 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i)
            j = n if j < 0 else j + 2
            out.append(" " * (j - i))
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def split_args(inner: str):
    """Split on top-level commas, also tracking generic parameter lists.

    `List<Pair<Peer, Int>>` must stay one argument, so `<`/`>` adjust depth too — but only when
    they cannot be a comparison operator or the `->` of a lambda signature.
    """
    args, depth, cur = [], 0, ""
    for index, ch in enumerate(inner):
        previous = inner[index - 1] if index else ""
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "<" and (previous.isalnum() or previous == "_"):
            depth += 1
        elif ch == ">" and depth > 0 and previous not in "-":
            depth -= 1
        if ch == "," and depth == 0:
            args.append(cur)
            cur = ""
        else:
            cur += ch
    args.append(cur)
    return [arg.strip() for arg in args if arg.strip()]


def call_args(text: str, open_paren: int):
    depth, j, n = 0, open_paren, len(text)
    while j < n:
        c = text[j]
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : j], j
        j += 1
    return None, n


def next_non_space(text: str, start: int) -> str:
    i = start
    while i < len(text) and text[i] in " \t\r\n":
        i += 1
    return text[i : i + 1]


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def members_of(text: str, header: str) -> dict:
    """{member: [(min_arity, max_arity), ...]} for functions/vals declared inside a class/interface block."""
    idx = text.find(header)
    if idx < 0:
        return {}
    brace = text.index("{", idx)
    depth, j = 0, brace
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                break
        j += 1
    body = text[brace + 1 : j]
    out = {}
    for m in re.finditer(r"\bfun\s+(\w+)\s*\(", body):
        name = m.group(1)
        inner, _ = call_args(body, m.end() - 1)
        if inner is None:
            continue
        params = split_args(inner)
        entry = (len([p for p in params if "=" not in p]), len(params))
        previous = out.get(name)
        if previous is None:
            out[name] = [entry]
        elif entry not in previous:
            previous.append(entry)
    for m in re.finditer(r"^\s*(?:override\s+|abstract\s+|open\s+)?(?:val|var)\s+(\w+)\b", body, re.M):
        out.setdefault(m.group(1), [(0, 0)])
    return out


def arity_matches(table: dict, name: str, arity: int) -> bool:
    if name not in table:
        return False
    return any(lo <= arity <= hi for lo, hi in table[name])


def main() -> int:
    errors = []
    clean = {}
    for path in SOURCES:
        raw = path.read_text()
        text = strip_noise(raw)
        clean[rel(path)] = text
        for open_ch, close_ch in (("(", ")"), ("{", "}"), ("[", "]")):
            if text.count(open_ch) != text.count(close_ch):
                errors.append(f"{rel(path)}: unbalanced {open_ch}{close_ch} {text.count(open_ch)}/{text.count(close_ch)}")

    domain_path = "core/domain/src/main/kotlin/ai/bale/client/domain/Models.kt"
    domain = clean.get(domain_path, "")
    interfaces = {
        "MessengerRepository": members_of(domain, "interface MessengerRepository"),
        "AuthRepository": members_of(domain, "interface AuthRepository"),
    }
    declared = dict(interfaces)
    for cls in ("MessagingApi", "DirectoryApi", "RichFeaturesApi", "FilesApi"):
        key = next((k for k in clean if k.endswith(f"/network/{cls}.kt")), None)
        declared[cls] = members_of(clean[key], f"class {cls}(") if key else {}
    declared["MessengerDao"] = members_of(clean["core/data/src/main/kotlin/ai/bale/client/data/BaleDatabase.kt"], "interface MessengerDao")
    declared["ClientPreferencesStore"] = members_of(clean["core/data/src/main/kotlin/ai/bale/client/data/ClientPreferencesStore.kt"], "class ClientPreferencesStore(")

    dataclasses = {}
    for m in re.finditer(r"data class (\w+)\s*\(", domain):
        name = m.group(1)
        inner, _ = call_args(domain, m.end() - 1)
        if inner is None:
            continue
        dataclasses[name] = {
            fm.group(1) for arg in split_args(inner) if (fm := re.match(r"(?:val|var)\s+(\w+)", arg.strip()))
        }

    receiver_map = {
        "session.messaging": "MessagingApi",
        "session.directory": "DirectoryApi",
        "session.rich": "RichFeaturesApi",
        "session.files": "FilesApi",
        "dao": "MessengerDao",
        "preferencesStore": "ClientPreferencesStore",
    }
    def implemented_interfaces(text: str):
        """Interface names listed in the primary class's supertype clause of this file."""
        names = set()
        for m in re.finditer(r"^(?:internal |private |public )?(?:data |open |abstract )?class \w+[^;{]*?\(", text, re.M):
            _, close = call_args(text, m.end() - 1)
            header_end = text.find("{", close)
            header = text[close : header_end if header_end > 0 else len(text)]
            if ":" not in header:
                continue
            supertypes = header[header.index(":") + 1 :]
            names.update(re.findall(r"\b([A-Z]\w*Repository)\b", supertypes))
        return names

    for path, text in clean.items():
        if "/src/main/kotlin/" not in path and "/src/test/kotlin/" not in path and "/src/androidTest/kotlin/" not in path:
            continue
        for recv, cls in receiver_map.items():
            table = declared.get(cls, {})
            for m in re.finditer(re.escape(recv) + r"\.(\w+)\s*\(", text):
                fn = m.group(1)
                if fn in IGNORED_MEMBERS:
                    continue
                if fn not in table:
                    errors.append(f"{path}:{line_of(text, m.start())}: {recv}.{fn}() not declared in {cls}")
                    continue
                inner, close = call_args(text, m.end() - 1)
                arity = len(split_args(inner)) if inner and inner.strip() else 0
                # A trailing lambda counts as one more argument than the parenthesised list shows.
                if next_non_space(text, close + 1) == "{":
                    arity += 1
                if not arity_matches(table, fn, arity):
                    errors.append(f"{path}:{line_of(text, m.start())}: {recv}.{fn}() called with {arity} args, {cls} expects {table[fn]}")

        owners = implemented_interfaces(text)
        for owner in sorted(owners):
            table = interfaces[owner]
            for m in re.finditer(r"^\s*override\s+(?:suspend\s+)?fun\s+(\w+)\s*\(", text, re.M):
                fn = m.group(1)
                if fn in IGNORED_MEMBERS:
                    continue
                inner, _ = call_args(text, m.end() - 1)
                arity = len(split_args(inner)) if inner and inner.strip() else 0
                if not arity_matches(table, fn, arity):
                    note = " (name absent)" if fn not in table else f" (declared {table[fn]})"
                    errors.append(f"{path}:{line_of(text, m.start())}: override fun {fn}/{arity} unmatched in {owner}{note}")

        for name, fields in dataclasses.items():
            for m in re.finditer(rf"\b{name}\s*\((?=\s*\w+\s*=)", text):
                inner, _ = call_args(text, m.end() - 1)
                for arg in split_args(inner or ""):
                    am = re.match(r"(\w+)\s*=", arg.strip())
                    if am and am.group(1) not in fields:
                        errors.append(f"{path}:{line_of(text, m.start())}: {name}( has unknown field '{am.group(1)}'")

    if errors:
        print("\n".join(errors))
        print(f"\n{len(errors)} consistency problem(s)")
        return 1
    print(f"Kotlin source consistency checks passed ({len(clean)} files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
