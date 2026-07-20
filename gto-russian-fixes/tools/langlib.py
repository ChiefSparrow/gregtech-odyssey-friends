from __future__ import annotations

import json
import re
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


LANG_RE = re.compile(r"^assets/([^/]+)/lang/(en_us|ru_ru)\.json$")
NESTED_JAR_RE = re.compile(r"^META-INF/jarjar/.+\.jar$")
RELEVANT_KEY_RE = re.compile(
    r"^(?:item|block|fluid|entity|effect|enchantment)\.|"
    r"^advancements\..*\.(?:title|description)$|"
    r"^(?:gui|screen|menu|container|cover|button)\.|"
    r"(?:^|\.)(?:tooltip|description|desc|title|subtitle|info)(?:\.|$)|"
    r"(?:tooltip|description|_desc|_info)(?:_|\.|$)",
    re.IGNORECASE,
)
QUEST_KEY_RE = re.compile(r"^gto\..*\.quests?\.", re.IGNORECASE)
INTENTIONAL_UNTRANSLATED_KEY_RE = re.compile(
    r"^enchantment\.level\.\d+$",
    re.IGNORECASE,
)
MOJIBAKE_RE = re.compile(
    r"(?:[À-ÖØ-öø-ÿ]{3,}|(?:Ð.|Ñ.){3,}|(?:Р.|С.){4,})"
)
FORMAT_TOKEN_RE = re.compile(
    r"%(?:\d+\$)?(?:\.\d+)?[sdif%]|"
    r"\{\d+\}|"
    r"§[0-9A-FK-ORa-fk-or]"
)


@dataclass(frozen=True)
class LangValue:
    value: str
    source: str
    namespace: str
    locale: str


@dataclass
class EffectiveLang:
    english: dict[str, LangValue]
    russian: dict[str, LangValue]
    english_quests: dict[str, LangValue]
    russian_quests: dict[str, LangValue]


def strip_json_comments(text: str) -> str:
    result: list[str] = []
    index = 0
    in_string = False
    escaped = False
    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""
        if in_string:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            index += 1
            continue
        if char == '"':
            in_string = True
            result.append(char)
            index += 1
            continue
        if char == "/" and next_char == "/":
            index += 2
            while index < len(text) and text[index] not in "\r\n":
                index += 1
            continue
        if char == "/" and next_char == "*":
            index += 2
            while index + 1 < len(text) and text[index : index + 2] != "*/":
                index += 1
            index += 2
            continue
        result.append(char)
        index += 1
    return "".join(result)


def read_json_object(raw: bytes, source: str) -> dict[str, str]:
    text = raw.decode("utf-8-sig")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        relaxed = strip_json_comments(text)
        relaxed = re.sub(r",(\s*[}\]])", r"\1", relaxed)
        try:
            parsed = json.loads(relaxed)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"Некорректный JSON локализации: {source}: {exc}"
            ) from exc
    if not isinstance(parsed, dict):
        raise ValueError(f"Корень локализации должен быть объектом: {source}")
    return {str(key): value for key, value in parsed.items() if isinstance(value, str)}


def iter_zip_lang(
    archive: zipfile.ZipFile,
    source: str,
    *,
    nested_depth: int = 0,
) -> Iterable[tuple[str, str, str, dict[str, str]]]:
    for info in archive.infolist():
        match = LANG_RE.fullmatch(info.filename)
        if match:
            namespace, locale = match.groups()
            yield source, namespace, locale, read_json_object(
                archive.read(info), f"{source}!{info.filename}"
            )
            continue
        if nested_depth < 1 and NESTED_JAR_RE.fullmatch(info.filename):
            from io import BytesIO

            with zipfile.ZipFile(BytesIO(archive.read(info))) as nested:
                nested_source = f"{source}!{info.filename}"
                yield from iter_zip_lang(
                    nested, nested_source, nested_depth=nested_depth + 1
                )


def iter_directory_lang(root: Path, source: str):
    if not root.exists():
        return
    for path in sorted(root.rglob("*.json")):
        relative = path.relative_to(root).as_posix()
        match = LANG_RE.fullmatch(relative)
        if not match:
            continue
        namespace, locale = match.groups()
        yield source, namespace, locale, read_json_object(
            path.read_bytes(), str(path)
        )


def apply_lang(
    target: dict[str, LangValue],
    quest_target: dict[str, LangValue],
    values: dict[str, str],
    *,
    source: str,
    namespace: str,
    locale: str,
) -> None:
    for key, value in values.items():
        item = LangValue(value=value, source=source, namespace=namespace, locale=locale)
        target[key] = item
        if namespace == "gto" or QUEST_KEY_RE.search(key):
            quest_target[key] = item


def load_effective_languages(instance: Path) -> EffectiveLang:
    english: dict[str, LangValue] = {}
    russian: dict[str, LangValue] = {}
    english_quests: dict[str, LangValue] = {}
    russian_quests: dict[str, LangValue] = {}

    mods = instance / "mods"
    for jar in sorted(mods.glob("*.jar"), key=lambda p: p.name.casefold()):
        try:
            with zipfile.ZipFile(jar) as archive:
                records = iter_zip_lang(archive, jar.name)
                for source, namespace, locale, values in records:
                    apply_lang(
                        english if locale == "en_us" else russian,
                        english_quests if locale == "en_us" else russian_quests,
                        values,
                        source=source,
                        namespace=namespace,
                        locale=locale,
                    )
        except zipfile.BadZipFile:
            continue

    openloader = instance / "config" / "openloader" / "resources"
    configured_packs: list[str] = []
    options = instance / "options.txt"
    if options.exists():
        for line in options.read_text(encoding="utf-8").splitlines():
            if not line.startswith("resourcePacks:"):
                continue
            try:
                pack_list = json.loads(line.removeprefix("resourcePacks:"))
            except json.JSONDecodeError:
                break
            configured_packs = [
                value.removeprefix("resources/")
                for value in pack_list
                if isinstance(value, str) and value.startswith("resources/")
            ]
            break
    if not configured_packs:
        configured_packs = [
            "quests",
            "resources",
            "z-gto-lang-all-locales-gto-0.5.6-r3.zip",
        ]

    for pack_name in configured_packs:
        path = openloader / pack_name
        if path.is_dir():
            records = iter_directory_lang(path, f"openloader/{pack_name}")
        elif path.is_file():
            archive = zipfile.ZipFile(path)
            try:
                records = list(iter_zip_lang(archive, f"openloader/{pack_name}", nested_depth=1))
            finally:
                archive.close()
        else:
            continue
        for source, namespace, locale, values in records:
            apply_lang(
                english if locale == "en_us" else russian,
                english_quests if locale == "en_us" else russian_quests,
                values,
                source=source,
                namespace=namespace,
                locale=locale,
            )

    return EffectiveLang(
        english=english,
        russian=russian,
        english_quests=english_quests,
        russian_quests=russian_quests,
    )


def namespace_from_key(key: str) -> str:
    parts = key.split(".")
    if len(parts) >= 3 and parts[0] in {
        "item",
        "block",
        "fluid",
        "entity",
        "effect",
        "enchantment",
    }:
        return parts[1]
    return "other"


def format_tokens(value: str) -> list[str]:
    return FORMAT_TOKEN_RE.findall(value)


def strip_formatting(value: str) -> str:
    return re.sub(r"§[0-9A-FK-ORa-fk-or]", "", value)


def contains_mojibake(value: str) -> bool:
    return bool(MOJIBAKE_RE.search(value))


def load_translation_file(path: Path) -> dict[str, dict[str, str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    defaults = document.get("defaults", {})
    normalized: dict[str, dict[str, str]] = {}
    for key, value in document["entries"].items():
        if isinstance(value, str):
            normalized[key] = {
                "ru": value,
                "status": defaults.get("status", "context_checked"),
                "category": defaults.get("category", "Ручной перевод"),
                "context": defaults.get(
                    "context",
                    "Ручной перевод с проверкой контекста.",
                ),
                **(
                    {"namespace": defaults["namespace"]}
                    if "namespace" in defaults
                    else {}
                ),
            }
        else:
            normalized[key] = value
    return normalized
