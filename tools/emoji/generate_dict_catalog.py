#!/usr/bin/env python3
"""Regenerate EmojiDictCatalog.kt from the wmkeyboard-data repo.

The repo carries one gzipped JSON emoji dictionary per language at
`data/<dir>/<code>_emoji.json.gz`, where the folder is the code itself except
for a pack filed beside its parent language's data (Banglish
`data/bn/bn_rom_emoji.json.gz`). This walks the repo tree, downloads each
one to count what it actually contains, maps the repo's codes onto the app's
`LanguageRegistry` ids, and writes the Kotlin table.

Three filters decide what makes the catalogue, and each exists because
offering the entry would be offering nothing:
  * packs under MIN_EMOJI are upstream stubs (one or two emoji);
  * DUPLICATE_CODES are byte-for-byte copies of a plainer code;
  * a code with no matching app language has nowhere to install to.

Usage:
  python3 tools/emoji/generate_dict_catalog.py             # network
  python3 tools/emoji/generate_dict_catalog.py --check     # verify, don't write
  python3 tools/emoji/generate_dict_catalog.py --tree t.json   # API rate-limited
"""

from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import json
import re
import sys
import urllib.request
from pathlib import Path

REPO = "wasi-master/wmkeyboard-data"
TREE_URL = f"https://api.github.com/repos/{REPO}/git/trees/HEAD?recursive=1"
RAW = f"https://raw.githubusercontent.com/{REPO}/HEAD"

# A pack this small is an upstream stub, not a translation.
MIN_EMOJI = 100

# Repo code -> app LanguageRegistry id, where the two spell it differently.
REMAP = {"no": "nb"}

# Identical to a plainer code already in the repo; the plain one wins.
DUPLICATE_CODES = {"fil", "pt_br", "zh_cn"}


def app_language_ids(root: Path) -> set[str]:
    src = (root / "core/language/src/main/java/com/wasimaster/wmkeyboard/core/script/Language.kt")
    return set(re.findall(r'\bid\s*=\s*"([A-Za-z0-9_-]+)"', src.read_text(encoding="utf-8")))


def emoji_paths(tree_file: Path | None = None) -> list[tuple[str, str, int]]:
    """(repo code, path, compressed size) for every emoji dictionary.

    The code is the file stem, not the folder: `bn/bn_rom_emoji.json.gz` is
    Banglish's pack, not a second Bengali one.

    The tree comes from the GitHub API, which rate-limits unauthenticated
    callers hard enough to block a rerun; `--tree` takes a saved copy instead
    (`curl -o tree.json <the API url>`).
    """
    if tree_file is not None:
        tree = json.loads(tree_file.read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(TREE_URL, timeout=60) as response:
            tree = json.load(response)
    if tree.get("truncated"):
        print("warning: repo tree was truncated; some packs may be missing", file=sys.stderr)
    return [
        (blob["path"].rsplit("/", 1)[1].removesuffix("_emoji.json.gz"), blob["path"], blob["size"])
        for blob in tree["tree"]
        if blob["path"].endswith("_emoji.json.gz")
    ]


def count(entry: tuple[str, str, int]) -> tuple[str, str, int, int]:
    code, path, size = entry
    with urllib.request.urlopen(f"{RAW}/{path}", timeout=120) as response:
        data = json.loads(gzip.decompress(response.read()))
    return code, path.split("/")[1], len(data), size


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--tree", type=Path, help="saved copy of the repo tree JSON")
    parser.add_argument(
        "--out", type=Path,
        default=root / "core/emoji/src/main/java/com/wasimaster/wmkeyboard/core/emoji/EmojiDictCatalog.kt",
    )
    args = parser.parse_args()

    languages = app_language_ids(root)
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        counted = list(pool.map(count, emoji_paths(args.tree)))

    rows, skipped = [], []
    for code, folder, emoji, size in sorted(counted):
        if code in DUPLICATE_CODES:
            skipped.append((code, "duplicate"))
            continue
        if emoji < MIN_EMOJI:
            skipped.append((code, f"only {emoji} emoji"))
            continue
        language = REMAP.get(code, code)
        if language not in languages:
            skipped.append((code, "no app language"))
            continue
        rows.append((language, code, folder, emoji, size))
    rows.sort()

    ids = [row[0] for row in rows]
    if len(set(ids)) != len(ids):
        print("two packs claim one language id", file=sys.stderr)
        return 1

    body = "\n".join(
        '        entry("{}", {}, {}L{}{}),'.format(
            language, emoji, size,
            "" if code == language else f', repoCode = "{code}"',
            "" if folder == code else f', repoDir = "{folder}"',
        )
        for language, code, folder, emoji, size in rows
    )
    existing = args.out.read_text(encoding="utf-8")
    head, _, rest = existing.partition("    val entries: List<EmojiDictEntry> = listOf(\n")
    _, _, tail = rest.partition("    )\n")
    generated = f"{head}    val entries: List<EmojiDictEntry> = listOf(\n{body}\n    )\n{tail}"

    if args.check:
        ok = generated == existing
        print("catalogue is up to date" if ok else "catalogue is STALE — rerun without --check")
        return 0 if ok else 1

    args.out.write_text(generated, encoding="utf-8")
    print(f"wrote {len(rows)} entries to {args.out}")
    for code, why in skipped:
        print(f"  skipped {code}: {why}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
