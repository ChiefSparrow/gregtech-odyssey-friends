from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

from langlib import (
    contains_mojibake,
    format_tokens,
    load_translation_file,
    load_effective_languages,
    strip_formatting,
)


CYRILLIC_RE = re.compile(r"[А-Яа-яЁё]")
DOUBLE_SPACE_RE = re.compile(r"(?<!§) {2,}")
SPACE_BEFORE_PUNCTUATION_RE = re.compile(r"\s+[,.!?;:]")
QUEST_FORMAT_RE = re.compile(r"&[0-9A-FK-ORa-fk-or]")
WORD_RE = re.compile(r"[A-Za-zА-Яа-яЁё-]+")


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def token_counter(value: str) -> Counter[str]:
    return Counter(format_tokens(value))


def quest_tokens(value: str) -> Counter[str]:
    return Counter(QUEST_FORMAT_RE.findall(value))


def phrase_pattern(value: str) -> re.Pattern[str]:
    return re.compile(
        rf"(?<![A-Za-z]){re.escape(strip_formatting(value))}(?![A-Za-z])",
        re.IGNORECASE,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument(
        "--project", type=Path, default=Path(__file__).resolve().parents[1]
    )
    args = parser.parse_args()

    project = args.project
    translations = load_json(project / "data" / "translations.json")
    entries: dict[str, dict[str, str]] = load_translation_file(
        project / "data" / "translations.json"
    )
    for supplemental_path in sorted(
        (project / "data").glob("*_translations.json")
    ):
        supplemental_entries = load_translation_file(supplemental_path)
        overlap = entries.keys() & supplemental_entries.keys()
        if overlap:
            raise ValueError(
                f"Повторяющиеся ключи в {supplemental_path.name}: "
                f"{sorted(overlap)}"
            )
        entries.update(supplemental_entries)
    allowlist = load_json(project / "data" / "allowlist.json")
    glossary = load_json(project / "data" / "glossary.json")
    effective = load_effective_languages(args.instance)

    errors: list[str] = []
    warnings: list[str] = []
    seen_ru: dict[str, str] = {}

    allowed_statuses = set(translations["meta"]["status_values"])
    exact_allowed = set(allowlist["exact_values"])
    allowed_key_patterns = [re.compile(p) for p in allowlist["key_patterns"]]
    allowed_value_patterns = [re.compile(p) for p in allowlist["value_patterns"]]

    for key, record in sorted(entries.items()):
        required = {"ru", "status", "category", "context"}
        missing_fields = required - record.keys()
        if missing_fields:
            errors.append(f"{key}: отсутствуют поля {sorted(missing_fields)}")
            continue
        ru = record["ru"]
        status = record["status"]
        if status not in allowed_statuses:
            errors.append(f"{key}: неизвестный статус {status!r}")
        english = effective.english.get(key)
        if english is None:
            errors.append(f"{key}: английский исходный ключ не найден")
            continue
        if not ru.strip():
            errors.append(f"{key}: пустой перевод")
        if (
            len(ru) - len(ru.lstrip()) != len(english.value) - len(english.value.lstrip())
            or len(ru) - len(ru.rstrip())
            != len(english.value) - len(english.value.rstrip())
        ):
            errors.append(
                f"{key}: служебные пробелы в начале или конце "
                "не совпадают с английским оригиналом"
            )
        if DOUBLE_SPACE_RE.search(ru.strip()):
            warnings.append(f"{key}: двойной пробел")
        if SPACE_BEFORE_PUNCTUATION_RE.search(ru):
            errors.append(f"{key}: пробел перед знаком препинания")
        if contains_mojibake(ru):
            errors.append(f"{key}: обнаружена повреждённая кодировка текста")
        if token_counter(english.value) != token_counter(ru):
            errors.append(
                f"{key}: различаются параметры/цветовые коды: "
                f"{token_counter(english.value)} != {token_counter(ru)}"
            )
        if "\\n" in english.value and english.value.count("\\n") != ru.count("\\n"):
            warnings.append(f"{key}: изменено количество явных переносов строк")
        if ru == english.value:
            stripped = strip_formatting(ru)
            is_allowed = (
                stripped in exact_allowed
                or any(p.search(key) for p in allowed_key_patterns)
                or any(p.fullmatch(stripped) for p in allowed_value_patterns)
            )
            if not is_allowed:
                errors.append(f"{key}: перевод совпадает с английским без исключения")
        if key.startswith(("item.", "block.", "fluid.", "entity.")) and not (
            CYRILLIC_RE.search(ru)
            or strip_formatting(ru) in exact_allowed
            or any(p.fullmatch(strip_formatting(ru)) for p in allowed_value_patterns)
        ):
            warnings.append(f"{key}: в названии нет кириллицы")
        if key in seen_ru and seen_ru[key] != ru:
            errors.append(f"{key}: конфликтующие переводы")
        seen_ru[key] = ru

        if key.startswith("gto.") or ".quests." in key:
            if quest_tokens(english.value) != quest_tokens(ru):
                errors.append(f"{key}: нарушены форматирующие коды квестбука")

    # Терминологические проверки по явно зафиксированным парам.
    for english_term, term in glossary["terms"].items():
        expected = term["ru"]
        accepted = [expected, *term.get("variants", [])]
        for key, record in entries.items():
            source = effective.english.get(key)
            if source and english_term in source.value and record.get("status") != "draft":
                if not any(
                    candidate.casefold() in record["ru"].casefold()
                    for candidate in accepted
                ):
                    warnings.append(
                        f"{key}: термин {english_term!r} не соответствует "
                        f"глоссарию {expected!r}"
                    )

    # Квестовая сверка: английское название нового предмета не должно оставаться
    # в русских строках квестбука.
    for key, record in entries.items():
        if not key.startswith(("item.", "block.", "fluid.")):
            continue
        english = effective.english.get(key)
        if english is None or len(strip_formatting(english.value)) < 4:
            continue
        pattern = phrase_pattern(english.value)
        for quest_key, quest_ru in effective.russian_quests.items():
            staged_quest = entries.get(quest_key, {}).get("ru", quest_ru.value)
            if pattern.search(staged_quest):
                errors.append(
                    f"{key}: английское название встречается в русском квесте "
                    f"{quest_key}"
                )

    for message in errors:
        print(f"ERROR: {message}")
    for message in warnings:
        print(f"WARN: {message}")
    print(
        f"Проверено переводов: {len(entries)}; "
        f"ошибок: {len(errors)}; предупреждений: {len(warnings)}"
    )
    if errors:
        sys.exit(1)


if __name__ == "__main__":
    main()
