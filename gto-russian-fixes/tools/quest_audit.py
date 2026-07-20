from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

from langlib import (
    load_effective_languages,
    load_translation_file,
    strip_formatting,
)


NAME_KEY_RE = re.compile(
    r"^(?:item|block|fluid|entity|effect|enchantment)\.",
    re.IGNORECASE,
)
LATIN_WORD_RE = re.compile(r"[A-Za-z]{3,}")


def phrase_pattern(value: str) -> re.Pattern[str]:
    plain = strip_formatting(value)
    return re.compile(
        rf"(?<![A-Za-z]){re.escape(plain)}(?![A-Za-z])",
        re.IGNORECASE,
    )


def write_csv(path: Path, rows: list[dict[str, str]], fields: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument(
        "--project",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    parser.add_argument(
        "--reports",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "reports",
    )
    args = parser.parse_args()

    effective = load_effective_languages(args.instance)
    translations = load_translation_file(
        args.project / "data" / "translations.json"
    )
    for supplemental_path in sorted(
        (args.project / "data").glob("*_translations.json")
    ):
        translations.update(load_translation_file(supplemental_path))

    crosscheck: list[dict[str, str]] = []
    raw_english: list[dict[str, str]] = []
    for item_key, record in sorted(translations.items()):
        if not NAME_KEY_RE.search(item_key) or record.get("status") == "draft":
            continue
        english = effective.english.get(item_key)
        if not english:
            continue
        plain_en = strip_formatting(english.value)
        if len(plain_en) < 4:
            continue
        pattern = phrase_pattern(english.value)
        for quest_key, quest_en in effective.english_quests.items():
            if not pattern.search(quest_en.value):
                continue
            quest_ru = effective.russian_quests.get(quest_key)
            staged = translations.get(quest_key, {}).get("ru")
            ru_value = staged if staged is not None else (
                quest_ru.value if quest_ru else ""
            )
            issue = ""
            if quest_ru is None:
                issue = "missing_russian_quest_string"
            elif pattern.search(ru_value):
                issue = "english_item_name_in_russian_quest"
                raw_english.append(
                    {
                        "item_key": item_key,
                        "english_name": english.value,
                        "russian_name": record["ru"],
                        "quest_key": quest_key,
                        "quest_russian": ru_value,
                        "russian_source": quest_ru.source,
                    }
                )
            crosscheck.append(
                {
                    "item_key": item_key,
                    "english_name": english.value,
                    "russian_name": record["ru"],
                    "quest_key": quest_key,
                    "quest_english": quest_en.value,
                    "quest_russian": ru_value,
                    "issue": issue,
                }
            )

    untranslated_quests: list[dict[str, str]] = []
    for key, english in sorted(effective.english_quests.items()):
        russian = effective.russian_quests.get(key)
        staged = translations.get(key, {}).get("ru")
        russian_value = staged if staged is not None else (
            russian.value if russian else None
        )
        if russian_value is None:
            untranslated_quests.append(
                {
                    "quest_key": key,
                    "english": english.value,
                    "russian": "",
                    "issue": "missing",
                    "english_source": english.source,
                    "russian_source": "",
                }
            )
        elif (
            russian_value == english.value
            and LATIN_WORD_RE.search(russian_value)
        ):
            untranslated_quests.append(
                {
                    "quest_key": key,
                    "english": english.value,
                    "russian": russian_value,
                    "issue": "same_as_english",
                    "english_source": english.source,
                    "russian_source": russian.source if russian else "project",
                }
            )

    write_csv(
        args.reports / "quest_crosscheck.csv",
        crosscheck,
        [
            "item_key",
            "english_name",
            "russian_name",
            "quest_key",
            "quest_english",
            "quest_russian",
            "issue",
        ],
    )
    write_csv(
        args.reports / "quest_raw_english_item_names.csv",
        raw_english,
        [
            "item_key",
            "english_name",
            "russian_name",
            "quest_key",
            "quest_russian",
            "russian_source",
        ],
    )
    write_csv(
        args.reports / "quest_untranslated.csv",
        untranslated_quests,
        [
            "quest_key",
            "english",
            "russian",
            "issue",
            "english_source",
            "russian_source",
        ],
    )
    print(
        f"Упоминаний переведённых названий в EN-квестах: {len(crosscheck)}; "
        f"сырых английских названий в RU-квестах: {len(raw_english)}; "
        f"непереведённых строк квестбука всего: {len(untranslated_quests)}"
    )


if __name__ == "__main__":
    main()
