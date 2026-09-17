#!/usr/bin/env python3
"""Pull Gemini-generated translations back out of a Play-built APK.

Play's automatic app strings translation injects translations into the app
bundle server-side. They never reach this repo, so the GitHub and F-Droid
builds ship English only. This script recovers them.

Get the APK from Play Console:
    Test and release > Latest releases and app bundles > App bundle explorer
    > pick the version > Downloads tab > "Signed, universal APK"

The universal APK merges every language split, so one file carries all locales.

    python3 tools/i18n/pull-play-translations.py path/to/universal.apk

Keys are matched against this repo's own values/strings*.xml, so strings from
AndroidX, Material and every other dependency are dropped automatically, and
each key lands back in the module that declares it.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MODULES = ["app"] + [
    str(p.relative_to(REPO))
    for p in sorted(REPO.glob("core/*"))
    if (p / "src/main/res/values").is_dir()
] + [
    str(p.relative_to(REPO))
    for p in sorted(REPO.glob("feature/*"))
    if (p / "src/main/res/values").is_dir()
]

RES_LINE = re.compile(r"^\s+resource 0x[0-9a-f]+ (string|plurals)/(\S+)")
VAL_LINE = re.compile(r'^\s+\(([^)]*)\)\s+"(.*)"\s*$')
PLURAL_HEAD = re.compile(r"^\s+\(([^)]*)\)\s+\(plurals\) size=\d+")
PLURAL_ITEM = re.compile(r'^\s+(zero|one|two|few|many|other)="(.*)"\s*$')
PLACEHOLDER = re.compile(r"%(\d+\$)?[sdfxeg]")
DASHES = re.compile(r"[–—]")


def find_aapt2() -> str:
    env = os.environ.get("AAPT2")
    if env and Path(env).exists():
        return env
    roots = [
        Path(os.environ.get("ANDROID_HOME", "")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "")),
        Path.home() / "Library/Android/sdk",
        Path.home() / "Android/Sdk",
    ]
    found = []
    for root in roots:
        if root and (root / "build-tools").is_dir():
            found += sorted((root / "build-tools").glob("*/aapt2"))
    if not found:
        sys.exit("No aapt2 found. Set AAPT2=/path/to/aapt2 or ANDROID_HOME.")
    return str(found[-1])


def dump(apk: Path) -> str:
    aapt2 = find_aapt2()
    out = subprocess.run(
        [aapt2, "dump", "resources", str(apk)],
        capture_output=True, text=True, check=False,
    )
    if out.returncode != 0:
        sys.exit(f"aapt2 failed:\n{out.stderr[:2000]}")
    return out.stdout


def parse_dump(text: str) -> dict:
    """-> {(kind, key): {locale: str | {quantity: str}}}"""
    table: dict = {}
    kind = key = None
    plural_locale = None
    for line in text.splitlines():
        m = RES_LINE.match(line)
        if m:
            kind, key = m.group(1), m.group(2)
            table.setdefault((kind, key), {})
            plural_locale = None
            continue
        if key is None:
            continue
        m = PLURAL_HEAD.match(line)
        if m:
            plural_locale = m.group(1)
            table[(kind, key)][plural_locale] = {}
            continue
        if plural_locale is not None:
            m = PLURAL_ITEM.match(line)
            if m:
                table[(kind, key)][plural_locale][m.group(1)] = m.group(2)
                continue
        m = VAL_LINE.match(line)
        if m:
            plural_locale = None
            table[(kind, key)][m.group(1)] = m.group(2)
    return table


def repo_keys() -> tuple[dict, set]:
    """-> ({(kind, key): module}, {keys marked translatable="false"})"""
    owner: dict = {}
    untranslatable: set = set()
    for module in MODULES:
        values = REPO / module / "src/main/res/values"
        for path in sorted(values.glob("strings*.xml")):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as exc:
                print(f"  ! unparseable {path}: {exc}", file=sys.stderr)
                continue
            for node in root:
                if node.tag not in ("string", "plurals"):
                    continue
                name = node.get("name")
                if not name:
                    continue
                if node.get("translatable") == "false":
                    untranslatable.add((node.tag, name))
                    continue
                owner[(node.tag, name)] = module
    return owner, untranslatable


def esc(value: str) -> str:
    out = (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", r"\'").replace('"', r"\"")
                .replace("\n", r"\n").replace("\t", r"\t"))
    if out[:1] in ("@", "?"):
        out = "\\" + out
    if out != out.strip():
        out = f'"{out}"'
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk", type=Path, help="Signed universal APK from Play Console")
    ap.add_argument("--dry-run", action="store_true", help="Report only, write nothing")
    ap.add_argument("--locale", action="append", default=[],
                    help="Only this locale qualifier (repeatable), e.g. de, pt-rBR")
    args = ap.parse_args()

    if not args.apk.exists():
        sys.exit(f"No APK at {args.apk}")

    print(f"Reading {args.apk} …")
    table = parse_dump(dump(args.apk))
    owner, untranslatable = repo_keys()
    print(f"  {len(table)} resources in APK, {len(owner)} translatable keys in repo, "
          f"{len(untranslatable)} marked translatable=false")

    # module -> locale -> [(kind, key, value)]
    buckets: dict = defaultdict(lambda: defaultdict(list))
    problems: list[str] = []
    skipped_untranslatable = 0

    for (kind, key), locales in table.items():
        if (kind, key) in untranslatable:
            if any(loc for loc in locales if loc):
                skipped_untranslatable += 1
            continue
        module = owner.get((kind, key))
        if module is None:
            continue  # library string, or a key this build no longer declares
        default = locales.get("")
        for locale, value in locales.items():
            if not locale:
                continue
            if args.locale and locale not in args.locale:
                continue
            if default is not None:
                for want, got, label in _compare(default, value):
                    problems.append(f"{locale}/{key} [{label}]: default {want} vs {got}")
            flat = " ".join(value.values()) if isinstance(value, dict) else value
            if DASHES.search(flat):
                problems.append(f"{locale}/{key}: en/em dash, house style forbids it")
            buckets[module][locale].append((kind, key, value))

    if skipped_untranslatable:
        print(f"  skipped {skipped_untranslatable} keys marked translatable=false")

    total = 0
    for module in sorted(buckets):
        for locale in sorted(buckets[module]):
            rows = sorted(buckets[module][locale], key=lambda r: r[1])
            total += len(rows)
            out = REPO / module / "src/main/res" / f"values-{locale}" / "strings.xml"
            print(f"  {out.relative_to(REPO)}  ({len(rows)})")
            if args.dry_run:
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(_render(rows), encoding="utf-8")

    print(f"\n{total} translated resources across "
          f"{len({l for m in buckets for l in buckets[m]})} locales"
          f"{' (dry run, nothing written)' if args.dry_run else ''}")

    if problems:
        print(f"\n{len(problems)} problems — fix in Play Console, then re-pull:")
        for line in problems[:60]:
            print(f"  ! {line}")
        if len(problems) > 60:
            print(f"  … and {len(problems) - 60} more")
        return 1
    return 0


def _compare(default, value):
    """Yield (expected, actual, label) for placeholder mismatches."""
    if isinstance(default, dict) != isinstance(value, dict):
        yield "plurals" if isinstance(default, dict) else "string", "mismatched shape", "shape"
        return
    if isinstance(default, dict):
        base = sorted(PLACEHOLDER.findall(default.get("other", "")))
        for quantity, text in value.items():
            got = sorted(PLACEHOLDER.findall(text))
            if got != base:
                yield base, got, quantity
        return
    base, got = sorted(PLACEHOLDER.findall(default)), sorted(PLACEHOLDER.findall(value))
    if base != got:
        yield base, got, "string"


def _render(rows) -> str:
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             "<!-- Machine translated by Google Play. Do not edit by hand:",
             "     regenerate with tools/i18n/pull-play-translations.py. -->",
             "<resources>"]
    for kind, key, value in rows:
        if kind == "string":
            lines.append(f'    <string name="{key}">{esc(value)}</string>')
        else:
            lines.append(f'    <plurals name="{key}">')
            for quantity in ("zero", "one", "two", "few", "many", "other"):
                if quantity in value:
                    lines.append(f'        <item quantity="{quantity}">'
                                 f'{esc(value[quantity])}</item>')
            lines.append("    </plurals>")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
