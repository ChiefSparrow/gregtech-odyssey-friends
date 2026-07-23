from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path
from urllib.parse import quote


MANUAL_DOWNLOADS = (
    {
        "name": "PandaLib 0.5.2",
        "project_id": 975460,
        "file_id": 6245108,
        "source_filename": "pandalib-forge-mc1.20-0.5.2-SNAPSHOT.jar",
        "destination": (
            "mods/API-pandalib-forge-mc1.20-0.5.2-SNAPSHOT.jar"
        ),
    },
    {
        "name": "HT's TreeChop 0.19.0 fixed",
        "project_id": 421377,
        "file_id": 5565422,
        "source_filename": "TreeChop-1.20.1-forge-0.19.0-fixed.jar",
        "destination": "mods/TreeChop-1.20.1-forge-0.19.0-fixed.jar",
    },
    {
        "name": "Panda's Falling Trees 0.13.2",
        "project_id": 880630,
        "file_id": 6231030,
        "source_filename": (
            "fallingtrees-forge-mc1.20-0.13.2-SNAPSHOT.jar"
        ),
        "destination": (
            "mods/fallingtrees-forge-mc1.20-0.13.2-SNAPSHOT.jar"
        ),
    },
    {
        "name": "Euphoria Patcher 1.7.7 r5.6.1 Forge",
        "project_id": 915902,
        "file_id": 7214963,
        "source_filename": "EuphoriaPatcher-1.7.7-r5.6.1-forge.jar",
        "destination": (
            "mods/CmplAdd-EuphoriaPatcher-1.7.7-r5.6.1-forge.jar"
        ),
    },
)

COMPLEMENTARY = {
    "name": "Complementary Reimagined r5.6.1",
    "project_id": 627557,
    "file_id": 7090226,
    "source_filename": "ComplementaryReimagined_r5.6.1.zip",
    "destination": "shaderpacks/ComplementaryReimagined_r5.6.1.zip",
    "size": 500696,
    "sha256": (
        "33153747D25FBEE470ACB42CCF4F26B03CC551BB900D4C6D3389B5500DD84839"
    ),
}

EXPECTED_LOCAL_PAYLOAD_MODS = {
    "mods/AE-Dark-UI-GTO-v0.5.6.0.zip.disabled",
    "mods/AE-Light-UI-GTO-v0.5.6.0.zip.disabled",
    "mods/gto-farming-fix-1.0.1.jar",
    "mods/gto-terminal-fix-1.0.0.jar",
    "mods/gtocore-forge-1.20.1-0.5.6-beta.jar",
    "mods/gtonativelib-1.0.jar",
}


def cdn_url(file_id: int, filename: str) -> str:
    first = file_id // 1000
    last = file_id % 1000
    encoded = quote(filename, safe="._-+()[]' ")
    encoded = encoded.replace(" ", "%20")
    return (
        f"https://edge.forgecdn.net/files/{first}/{last:03d}/{encoded}"
    )


def lock_files_by_path(lock_path: Path) -> dict[str, dict[str, object]]:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    if lock.get("schema") != "gto-friends-client-lock/v1":
        raise ValueError(f"Unsupported lock schema in {lock_path}")
    return {entry["path"]: entry for entry in lock["files"]}


def metadata_value(text: str, key: str) -> str:
    match = re.search(
        rf"(?m)^{re.escape(key)}\s*=\s*(.+?)\s*$",
        text,
    )
    if not match:
        raise ValueError(f"Missing metadata key: {key}")
    value = match.group(1).strip()
    if len(value) >= 2 and value[0] in {"'", '"'} and value[-1] == value[0]:
        value = value[1:-1]
    return value


def lock_entry(
    locked: dict[str, dict[str, object]], destination: str
) -> dict[str, object]:
    try:
        return locked[destination]
    except KeyError as exc:
        raise ValueError(f"Destination is absent from client lock: {destination}") from exc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--instance", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    locked = lock_files_by_path(args.lock)
    index_dir = args.instance / "mods" / ".index"
    if not index_dir.is_dir():
        raise FileNotFoundError(f"Prism metadata directory not found: {index_dir}")

    rows: list[dict[str, object]] = []
    for metadata_path in sorted(index_dir.glob("*.pw.toml")):
        metadata = metadata_path.read_text(encoding="utf-8")
        filename = metadata_value(metadata, "filename")
        project_id = int(metadata_value(metadata, "project-id"))
        file_id = int(metadata_value(metadata, "file-id"))

        enabled_path = f"mods/{filename}"
        disabled_path = f"{enabled_path}.disabled"
        matches = [path for path in (enabled_path, disabled_path) if path in locked]
        if len(matches) != 1:
            raise ValueError(
                f"Expected one lock destination for {metadata_path.name}, got {matches}"
            )
        destination = matches[0]
        expected = lock_entry(locked, destination)
        rows.append(
            {
                "destination": destination,
                "size": expected["size"],
                "sha256": expected["sha256"],
                "url": cdn_url(file_id, filename),
                "project_id": project_id,
                "file_id": file_id,
                "name": metadata_value(metadata, "name"),
            }
        )

    for manual in MANUAL_DOWNLOADS:
        expected = lock_entry(locked, manual["destination"])
        rows.append(
            {
                "destination": manual["destination"],
                "size": expected["size"],
                "sha256": expected["sha256"],
                "url": cdn_url(
                    int(manual["file_id"]), str(manual["source_filename"])
                ),
                "project_id": manual["project_id"],
                "file_id": manual["file_id"],
                "name": manual["name"],
            }
        )

    rows.append(
        {
            "destination": COMPLEMENTARY["destination"],
            "size": COMPLEMENTARY["size"],
            "sha256": COMPLEMENTARY["sha256"],
            "url": cdn_url(
                int(COMPLEMENTARY["file_id"]),
                str(COMPLEMENTARY["source_filename"]),
            ),
            "project_id": COMPLEMENTARY["project_id"],
            "file_id": COMPLEMENTARY["file_id"],
            "name": COMPLEMENTARY["name"],
        }
    )

    destinations = [str(row["destination"]) for row in rows]
    duplicates = sorted(
        destination
        for destination in set(destinations)
        if destinations.count(destination) > 1
    )
    if duplicates:
        raise ValueError(f"Duplicate download destinations: {duplicates}")

    remote_mods = {
        destination for destination in destinations if destination.startswith("mods/")
    }
    locked_mods = {
        path for path, entry in locked.items() if entry["kind"] == "mod"
    }
    local_payload_mods = locked_mods - remote_mods
    if local_payload_mods != EXPECTED_LOCAL_PAYLOAD_MODS:
        raise ValueError(
            "Unexpected local payload mod set: "
            f"{sorted(local_payload_mods)}"
        )
    if len(remote_mods) != 170 or len(rows) != 171:
        raise ValueError(
            f"Unexpected manifest size: {len(remote_mods)} mods, {len(rows)} total"
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=(
                "destination",
                "size",
                "sha256",
                "url",
                "project_id",
                "file_id",
                "name",
            ),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(sorted(rows, key=lambda row: str(row["destination"]).lower()))

    print(f"Written: {args.output}")
    print(f"Remote mod files: {len(remote_mods)}")
    print(f"Other remote files: {len(rows) - len(remote_mods)}")
    print(f"Local payload mod files: {len(local_payload_mods)}")


if __name__ == "__main__":
    main()
