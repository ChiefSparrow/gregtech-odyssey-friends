from __future__ import annotations

import json
from pathlib import Path


PROJECT = Path(__file__).resolve().parents[1]
SERIES = {
    "agon": "Агон",
    "azur": "Азур",
    "bitt": "Битт",
    "cray": "Крэй",
    "fort": "Форт",
    "glaxx": "Глакс",
    "iszm": "ISZM",
    "jelt": "Джелт",
    "korp": "Корп",
    "kryp": "Крип",
    "lair": "Лэйр",
    "lave": "Лэйв",
    "mint": "Минт",
    "myst": "Мист",
    "reds": "Редс",
    "reed": "Рид",
    "roen": "Роэн",
    "sols": "Солс",
    "sync": "Синк",
    "tank": "Танк",
    "vect": "Вект",
    "vena": "Вена",
    "zane": "Зейн",
    "zech": "Зек",
    "zest": "Зест",
    "zeta": "Зета",
    "zion": "Зайон",
    "zkul": "Зкул",
    "zoea": "Зоэа",
    "zome": "Зоум",
    "zone": "Зоун",
    "ztyl": "Зтайл",
    "zyth": "Зит",
}


def main() -> None:
    entries: dict[str, str] = {
        "block.xtonesreworked.flat_lamp": "Плоская лампа",
        "block.xtonesreworked.xtone_tile": "Плитка Xtones",
    }
    for slug, russian in SERIES.items():
        entries[f"block.xtonesreworked.{slug}_block_0"] = russian
        for variant in range(1, 16):
            entries[
                f"block.xtonesreworked.{slug}_block_{variant}"
            ] = f"{russian}, вариант {variant}"
    document = {
        "defaults": {
            "status": "linguistic_checked",
            "category": "Xtones Reworked",
            "context": (
                "Серийная локализация: имя коллекции транслитерировано, "
                "номер варианта сохранён без изменения."
            ),
            "namespace": "xtonesreworked",
        },
        "entries": dict(sorted(entries.items())),
    }
    output = PROJECT / "data" / "xtones_translations.json"
    output.write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"{output}: {len(entries)} строк")


if __name__ == "__main__":
    main()
