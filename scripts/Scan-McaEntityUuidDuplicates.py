#!/usr/bin/env python3
"""Read-only scanner for duplicate entity UUIDs in Minecraft Anvil entity regions."""

from __future__ import annotations

import argparse
import gzip
import json
import re
import struct
import zlib
from collections import defaultdict
from pathlib import Path
from typing import Any


def read_nbt_string(data: bytes, offset: int) -> tuple[str, int]:
    size = struct.unpack_from(">H", data, offset)[0]
    offset += 2
    return data[offset : offset + size].decode("utf-8"), offset + size


def read_payload(data: bytes, offset: int, tag_type: int) -> tuple[Any, int]:
    if tag_type == 0:
        return None, offset
    if tag_type == 1:
        return struct.unpack_from(">b", data, offset)[0], offset + 1
    if tag_type == 2:
        return struct.unpack_from(">h", data, offset)[0], offset + 2
    if tag_type == 3:
        return struct.unpack_from(">i", data, offset)[0], offset + 4
    if tag_type == 4:
        return struct.unpack_from(">q", data, offset)[0], offset + 8
    if tag_type == 5:
        return struct.unpack_from(">f", data, offset)[0], offset + 4
    if tag_type == 6:
        return struct.unpack_from(">d", data, offset)[0], offset + 8
    if tag_type == 7:
        count = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        return data[offset : offset + count], offset + count
    if tag_type == 8:
        return read_nbt_string(data, offset)
    if tag_type == 9:
        element_type = data[offset]
        count = struct.unpack_from(">i", data, offset + 1)[0]
        offset += 5
        values = []
        for _ in range(count):
            value, offset = read_payload(data, offset, element_type)
            values.append(value)
        return values, offset
    if tag_type == 10:
        values = {}
        while True:
            child_type = data[offset]
            offset += 1
            if child_type == 0:
                return values, offset
            name, offset = read_nbt_string(data, offset)
            value, offset = read_payload(data, offset, child_type)
            values[name] = value
    if tag_type == 11:
        count = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        return list(struct.unpack_from(f">{count}i", data, offset)), offset + count * 4
    if tag_type == 12:
        count = struct.unpack_from(">i", data, offset)[0]
        offset += 4
        return list(struct.unpack_from(f">{count}q", data, offset)), offset + count * 8
    raise ValueError(f"Unsupported NBT tag type {tag_type} at offset {offset}")


def inflate_chunk(record: bytes, chunk: str) -> bytes:
    length = struct.unpack_from(">I", record, 0)[0]
    if length < 1 or length + 4 > len(record):
        raise ValueError(f"Invalid chunk record length {length} in {chunk}")
    compression = record[4]
    payload = record[5 : 4 + length]
    compression_type = compression & 0x7F
    if compression & 0x80:
        raise ValueError(f"External chunk stream is not supported in {chunk}")
    if compression_type == 1:
        return gzip.decompress(payload)
    if compression_type == 2:
        return zlib.decompress(payload)
    if compression_type == 3:
        return payload
    raise ValueError(f"Unsupported chunk compression {compression} in {chunk}")


def chunk_entities(path: Path) -> list[tuple[str, dict[str, Any]]]:
    match = re.fullmatch(r"r\.(-?\d+)\.(-?\d+)\.mca", path.name)
    if not match:
        return []
    region_x, region_z = map(int, match.groups())
    data = path.read_bytes()
    if len(data) < 8192:
        raise ValueError(f"Region file is shorter than its headers: {path}")
    found = []
    for index in range(1024):
        location = struct.unpack_from(">I", data, index * 4)[0]
        sector_offset, sector_count = location >> 8, location & 0xFF
        if sector_offset == 0 or sector_count == 0:
            continue
        start = sector_offset * 4096
        record = data[start : start + sector_count * 4096]
        chunk_x = region_x * 32 + index % 32
        chunk_z = region_z * 32 + index // 32
        raw = inflate_chunk(record, f"{path.name} chunk {chunk_x},{chunk_z}")
        if not raw:
            continue
        root_type = raw[0]
        if root_type != 10:
            raise ValueError(f"Expected compound root in {path} chunk {chunk_x},{chunk_z}")
        _, offset = read_nbt_string(raw, 1)
        root, _ = read_payload(raw, offset, root_type)
        for entity in root.get("Entities", []):
            uuid = entity.get("UUID")
            if isinstance(uuid, list) and len(uuid) == 4:
                most = ((uuid[0] & 0xFFFFFFFF) << 32) | (uuid[1] & 0xFFFFFFFF)
                least = ((uuid[2] & 0xFFFFFFFF) << 32) | (uuid[3] & 0xFFFFFFFF)
                uuid_text = f"{most:016x}{least:016x}"
            elif isinstance(uuid, str):
                uuid_text = uuid
            else:
                continue
            pos = entity.get("Pos")
            found.append(
                (
                    uuid_text,
                    {
                        "chunk": [chunk_x, chunk_z],
                        "position": pos,
                        "id": entity.get("id"),
                    },
                )
            )
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("entity_region_directory", type=Path)
    args = parser.parse_args()
    records: dict[str, list[dict[str, Any]]] = defaultdict(list)
    files = sorted(args.entity_region_directory.glob("r.*.*.mca"))
    for path in files:
        for uuid, location in chunk_entities(path):
            records[uuid].append({"region": path.name, **location})
    duplicates = {uuid: locations for uuid, locations in records.items() if len(locations) > 1}
    print(
        json.dumps(
            {
                "directory": str(args.entity_region_directory.resolve()),
                "regionFiles": len(files),
                "entityRecords": sum(map(len, records.values())),
                "uniqueUuids": len(records),
                "duplicateUuids": duplicates,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
