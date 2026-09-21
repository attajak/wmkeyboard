#!/usr/bin/env python3
"""Regenerate the docs' language/wordlist tables from the app's registries.

Reads the Kotlin sources (regex-based; the registries are declarative enough
for that to be safe) and writes:

  src/data/languages.json   — every LanguageDef: id, name, English name, script,
                              and whether it came from the converted Keyman set
  src/data/wordlists.json   — every DictionaryCatalog entry: id, language,
                              English name, word count, gz size

Run from docs/:  python3 scripts/extract_data.py
Rerun whenever LanguageRegistry or DictionaryCatalog changes.
"""
import json
import pathlib
import re

DOCS = pathlib.Path(__file__).resolve().parent.parent
REPO = DOCS.parent
LANG_KT = REPO / "core/language/src/main/java/com/wasimaster/wmkeyboard/core/script/Language.kt"
# The generated half of the registry. Same LanguageDef shape, separate file
# because it is rewritten wholesale by the Keyman pipeline.
KEYMAN_KT = REPO / "core/language/src/main/java/com/wasimaster/wmkeyboard/core/script/KeymanLanguages.kt"
DICT_KT = REPO / "core/prediction/src/main/java/com/wasimaster/wmkeyboard/core/dictionaries/DictionaryCatalog.kt"
OUT = DOCS / "src/data"

def parse_languages(path, keyman):
    src = path.read_text(encoding="utf-8")
    out = []
    for block in re.finditer(r"LanguageDef\(\s*(.*?)\n\s*\)[,\s]", src, re.S):
        body = block.group(1)
        def field(name):
            m = re.search(rf'{name}\s*=\s*"((?:[^"\\]|\\.)*)"', body)
            return m.group(1) if m else None
        ident = field("id")
        if not ident:
            continue
        script = re.search(r"script\s*=\s*ScriptId\.(\w+)", body)
        out.append({
            "id": ident,
            "name": field("displayName"),
            "english": field("englishName"),
            "script": (script.group(1).replace("_", " ").title() if script else ""),
            "keyman": keyman,
        })
    return out

langs = parse_languages(LANG_KT, keyman=False)
if KEYMAN_KT.exists():
    langs += parse_languages(KEYMAN_KT, keyman=True)
# The registry's GENERIC fallback ("und", never surfaced in UI) is not a language.
langs = [l for l in langs if l["id"] != "und"]
assert len(langs) > 800, f"suspiciously few languages parsed: {len(langs)}"

by_id = {l["id"]: l for l in langs}

dict_src = DICT_KT.read_text(encoding="utf-8")
lists = []
# `entry(` is a counted list, `aosp(` one of AOSP LatinIME's.
entry_re = re.compile(
    r'\b(entry|aosp)\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*([\d_]+),\s*([\d_]+)L'
    r'(?:,\s*variant\s*=\s*"([^"]+)")?'
)
for m in entry_re.finditer(dict_src):
    kind, ident, lang_id, _repo, words, gz, variant = m.groups()
    lang = by_id.get(lang_id)
    name = (lang["name"] if lang else lang_id) + (f" ({variant})" if variant else "")
    english = (lang["english"] if lang else lang_id) + (f" ({variant})" if variant else "")
    lists.append({
        "id": ident,
        "lang": lang_id,
        "name": name,
        "english": english,
        "words": int(words.replace("_", "")),
        "gzBytes": int(gz.replace("_", "")),
        "source": "AOSP" if kind == "aosp" else "Frequency",
    })
assert len(lists) > 300, f"suspiciously few wordlists parsed: {len(lists)}"

OUT.mkdir(parents=True, exist_ok=True)
(OUT / "languages.json").write_text(
    json.dumps(langs, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
(OUT / "wordlists.json").write_text(
    json.dumps(lists, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"languages: {len(langs)}  wordlists: {len(lists)}")
