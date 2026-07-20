from __future__ import annotations

import json
import shutil
import zipfile
from collections import defaultdict
from pathlib import Path

from langlib import load_translation_file

PROJECT = Path(__file__).resolve().parents[1]


def main() -> None:
    translations = json.loads(
        (PROJECT / "data" / "translations.json").read_text(encoding="utf-8")
    )
    version = translations["meta"]["version"]
    entries: dict[str, dict[str, str]] = load_translation_file(
        PROJECT / "data" / "translations.json"
    )
    for supplemental_path in sorted(
        (PROJECT / "data").glob("*_translations.json")
    ):
        supplemental_entries = load_translation_file(supplemental_path)
        overlap = entries.keys() & supplemental_entries.keys()
        if overlap:
            raise ValueError(
                f"Повторяющиеся ключи в {supplemental_path.name}: "
                f"{sorted(overlap)}"
            )
        entries.update(supplemental_entries)

    grouped: dict[str, dict[str, str]] = defaultdict(dict)
    for key, record in sorted(entries.items()):
        if record["status"] == "draft":
            continue
        parts = key.split(".")
        if record.get("namespace"):
            namespace = record["namespace"]
        elif len(parts) >= 3 and parts[0] in {
            "item",
            "block",
            "fluid",
            "entity",
            "effect",
            "enchantment",
        }:
            namespace = parts[1]
        elif key.startswith("gto."):
            namespace = "gto"
        elif key.startswith("gtocore."):
            namespace = "gtocore"
        elif key.startswith("gtceu."):
            namespace = "gtceu"
        else:
            namespace = "minecraft"
        grouped[namespace][key] = record["ru"]

    generated_assets = PROJECT / "pack" / "assets"
    if generated_assets.exists():
        shutil.rmtree(generated_assets)
    for namespace, values in grouped.items():
        lang_dir = generated_assets / namespace / "lang"
        lang_dir.mkdir(parents=True, exist_ok=True)
        (lang_dir / "ru_ru.json").write_text(
            json.dumps(values, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    dist = PROJECT / "dist"
    dist.mkdir(exist_ok=True)
    output = dist / f"z-gto-russian-fixes-0.5.6-v{version}.zip"
    if output.exists():
        output.unlink()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted((PROJECT / "pack").rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(PROJECT / "pack").as_posix())
    print(output)


if __name__ == "__main__":
    main()
