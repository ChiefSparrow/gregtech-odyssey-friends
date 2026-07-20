from __future__ import annotations

import argparse
import json
import subprocess
from collections import defaultdict
from pathlib import Path

from langlib import (
    INTENTIONAL_UNTRANSLATED_KEY_RE,
    RELEVANT_KEY_RE,
    contains_mojibake,
    load_effective_languages,
)


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save_json(path: Path, value: dict) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def has_cyrillic(value: str) -> bool:
    return any(
        "\u0400" <= character <= "\u052f"
        for character in value
    )


def category_for(key: str) -> str:
    parts = key.split(".")
    namespace = parts[1] if len(parts) > 2 else "other"
    if key.startswith("item."):
        kind = "предметы"
    elif key.startswith("block."):
        kind = "блоки"
    elif "tooltip" in key or key.endswith((".desc", ".description")):
        kind = "описания"
    elif key.startswith("entity."):
        kind = "существа"
    else:
        kind = "интерфейс"
    return f"{namespace}: {kind}"


def add_entry(
    entries: dict[str, dict],
    *,
    key: str,
    ru: str,
    source: str,
    source_key: str | None = None,
    namespace: str | None = None,
) -> bool:
    if key in entries:
        return False
    record = {
        "ru": ru,
        "status": "context_checked",
        "category": category_for(key),
        "context": source,
    }
    if source_key:
        record["source_key"] = source_key
    if namespace:
        record["namespace"] = namespace
    entries[key] = record
    return True


def import_translation_memory(
    instance: Path,
    entries: dict[str, dict],
) -> int:
    effective = load_effective_languages(instance)
    memory: dict[tuple[str, str], list[tuple[str, str, str]]] = defaultdict(list)
    for key, english in effective.english.items():
        russian = effective.russian.get(key)
        if (
            russian
            and russian.value != english.value
            and has_cyrillic(russian.value)
            and not contains_mojibake(russian.value)
        ):
            memory[(english.namespace, english.value)].append(
                (key, russian.value, russian.source)
            )

    added = 0
    for key, english in effective.english.items():
        if (
            key in effective.russian
            or not RELEVANT_KEY_RE.search(key)
            or INTENTIONAL_UNTRANSLATED_KEY_RE.search(key)
        ):
            continue
        candidates = memory.get((english.namespace, english.value), [])
        distinct = {candidate[1] for candidate in candidates}
        if len(distinct) != 1:
            continue
        source_key, ru, source = candidates[0]
        # В старом русификаторе несколько значений начинаются с латинской C.
        # Это безопасная орфографическая нормализация, а не новый перевод.
        if ru.startswith("Cущность "):
            ru = "Сущность " + ru[len("Cущность ") :]
        added += add_entry(
            entries,
            key=key,
            ru=ru,
            source=(
                "Точное совпадение английской строки с уже переведённым ключом "
                f"{source_key}; источник русского варианта: {source}."
            ),
            source_key=source_key,
            namespace=english.namespace,
        )
    return added


def git_json(repository: Path, specification: str) -> dict[str, str]:
    raw = subprocess.check_output(
        ["git", "-C", str(repository), "show", specification]
    )
    return json.loads(raw.decode("utf-8-sig"))


def import_upstream(
    instance: Path,
    upstream_root: Path,
    entries: dict[str, dict],
) -> int:
    effective = load_effective_languages(instance)
    sources = [
        {
            "name": "Functional Storage",
            "repository": upstream_root / "functionalstorage",
            "english": (
                "1.20.1-1.2.13:"
                "src/generated/resources/assets/functionalstorage/lang/en_us.json"
            ),
            "russian": (
                "origin/main:"
                "src/main/resources/assets/functionalstorage/lang/ru_ru.json"
            ),
        },
        {
            "name": "Deeper and Darker",
            "repository": upstream_root / "deeperdarker",
            "english": (
                "origin/forge-1.20:"
                "src/generated/resources/assets/deeperdarker/lang/en_us.json"
            ),
            "russian": (
                "origin/forge-1.20:"
                "src/main/resources/assets/deeperdarker/lang/ru_ru.json"
            ),
        },
    ]

    added = 0
    for source in sources:
        repository = source["repository"]
        if not repository.is_dir():
            continue
        upstream_en = git_json(repository, source["english"])
        upstream_ru = git_json(repository, source["russian"])
        for key, ru in upstream_ru.items():
            current_en = effective.english.get(key)
            if (
                not current_en
                or key in effective.russian
                or upstream_en.get(key) != current_en.value
                or not has_cyrillic(ru)
                or contains_mojibake(ru)
            ):
                continue
            added += add_entry(
                entries,
                key=key,
                ru=ru,
                source=(
                    f"Перевод из официального репозитория {source['name']}; "
                    "английская строка побайтно совпадает с версией в сборке."
                ),
                namespace=current_en.namespace,
            )
    return added


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", required=True, type=Path)
    parser.add_argument("--upstream-root", type=Path)
    parser.add_argument(
        "--project",
        type=Path,
        default=Path(__file__).resolve().parents[1],
    )
    args = parser.parse_args()

    path = args.project / "data" / "translations.json"
    translations = load_json(path)
    entries = translations["entries"]
    memory_count = import_translation_memory(args.instance, entries)
    upstream_count = 0
    if args.upstream_root:
        upstream_count = import_upstream(
            args.instance,
            args.upstream_root,
            entries,
        )
    effective = load_effective_languages(args.instance)
    for key, record in entries.items():
        if "namespace" not in record and key in effective.english:
            record["namespace"] = effective.english[key].namespace
    entries_sorted = dict(sorted(entries.items()))
    translations["entries"] = entries_sorted
    save_json(path, translations)
    print(
        f"Добавлено из памяти переводов: {memory_count}; "
        f"из официальных репозиториев: {upstream_count}; "
        f"всего записей: {len(entries_sorted)}"
    )


if __name__ == "__main__":
    main()
