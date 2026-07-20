from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path

from langlib import (
    INTENTIONAL_UNTRANSLATED_KEY_RE,
    RELEVANT_KEY_RE,
    contains_mojibake,
    load_translation_file,
    load_effective_languages,
)


LATIN_WORD_RE = re.compile(r"[A-Za-z]{3,}")


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
        "--reports",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "reports",
    )
    parser.add_argument(
        "--project",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    args = parser.parse_args()

    effective = load_effective_languages(args.instance)
    staged: dict[str, dict[str, str]] = {}
    paths = [
        args.project / "data" / "translations.json",
        *sorted((args.project / "data").glob("*_translations.json")),
    ]
    for path in paths:
        if path.exists():
            staged.update(
                {
                    key: value
                    for key, value in load_translation_file(path).items()
                    if value.get("status") != "draft"
                }
            )

    missing: list[dict[str, str]] = []
    same: list[dict[str, str]] = []
    mixed: list[dict[str, str]] = []
    mojibake: list[dict[str, str]] = []

    for key, english in sorted(effective.english.items()):
        if (
            not RELEVANT_KEY_RE.search(key)
            or INTENTIONAL_UNTRANSLATED_KEY_RE.search(key)
        ):
            continue
        russian = effective.russian.get(key)
        staged_record = staged.get(key)
        russian_value = (
            staged_record["ru"] if staged_record else (
                russian.value if russian else None
            )
        )
        russian_source = (
            "project" if staged_record else (
                russian.source if russian else ""
            )
        )
        base = {
            "namespace": english.namespace,
            "key": key,
            "english": english.value,
            "english_source": english.source,
        }
        if russian_value is None:
            missing.append(base)
            continue
        if russian_value == english.value and LATIN_WORD_RE.search(russian_value):
            same.append(
                {
                    **base,
                    "russian": russian_value,
                    "russian_source": russian_source,
                }
            )
        if LATIN_WORD_RE.search(russian_value) and re.search(r"[А-Яа-яЁё]", russian_value):
            mixed.append(
                {
                    **base,
                    "russian": russian_value,
                    "russian_source": russian_source,
                }
            )
        if contains_mojibake(russian_value):
            mojibake.append(
                {
                    **base,
                    "russian": russian_value,
                    "russian_source": russian_source,
                }
            )

    reports = args.reports
    write_csv(
        reports / "missing_relevant.csv",
        missing,
        ["namespace", "key", "english", "english_source"],
    )
    write_csv(
        reports / "same_as_english.csv",
        same,
        [
            "namespace",
            "key",
            "english",
            "english_source",
            "russian",
            "russian_source",
        ],
    )
    write_csv(
        reports / "mixed_language.csv",
        mixed,
        [
            "namespace",
            "key",
            "english",
            "english_source",
            "russian",
            "russian_source",
        ],
    )
    write_csv(
        reports / "mojibake.csv",
        mojibake,
        [
            "namespace",
            "key",
            "english",
            "english_source",
            "russian",
            "russian_source",
        ],
    )

    summary = {
        "english_total": len(effective.english),
        "russian_total": len(effective.russian),
        "english_quest_total": len(effective.english_quests),
        "russian_quest_total": len(effective.russian_quests),
        "staged_translation_total": len(staged),
        "missing_relevant": len(missing),
        "same_as_english": len(same),
        "mixed_language": len(mixed),
        "mojibake": len(mojibake),
        "missing_by_namespace": dict(
            Counter(row["namespace"] for row in missing).most_common()
        ),
    }
    reports.mkdir(parents=True, exist_ok=True)
    (reports / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
