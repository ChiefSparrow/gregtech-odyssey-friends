from __future__ import annotations

import argparse
import re
from pathlib import Path

from langlib import load_effective_languages


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("pattern")
    parser.add_argument("--quests", action="store_true")
    args = parser.parse_args()

    pattern = re.compile(args.pattern, re.IGNORECASE)
    effective = load_effective_languages(args.instance)
    english = effective.english_quests if args.quests else effective.english
    russian = effective.russian_quests if args.quests else effective.russian
    keys = sorted(set(english) | set(russian))
    for key in keys:
        en = english.get(key)
        ru = russian.get(key)
        searchable = "\n".join(
            part for part in (key, en.value if en else "", ru.value if ru else "") if part
        )
        if not pattern.search(searchable):
            continue
        print(key)
        print(f"  EN: {en.value if en else '<missing>'}")
        print(f"  RU: {ru.value if ru else '<missing>'}")
        print(f"  EN source: {en.source if en else '<missing>'}")
        print(f"  RU source: {ru.source if ru else '<missing>'}")


if __name__ == "__main__":
    main()
