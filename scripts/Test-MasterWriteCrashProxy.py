#!/usr/bin/env python3
"""Disposable test proxy that withholds the second chunk write in a pair."""

import argparse
import asyncio
import json
import os
import struct
import subprocess
import time
from pathlib import Path

WRITE_CHUNK_MESSAGE_ID = 35  # MasterBoundProtocol registration order in this revision.


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while shift < 35:
        if offset >= len(data):
            raise ValueError("truncated VarInt")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ValueError("VarInt too long")


def read_string(data: bytes, offset: int) -> tuple[str, int]:
    length, offset = read_varint(data, offset)
    end = offset + length
    if end > len(data):
        raise ValueError("truncated string")
    return data[offset:end].decode("utf-8"), end


def chunk_write(payload: bytes):
    try:
        _, offset = read_varint(payload, 0)  # transaction ID
        message_id, offset = read_varint(payload, offset)
        if message_id != WRITE_CHUNK_MESSAGE_ID:
            return None
        world, offset = read_string(payload, offset)
        path, offset = read_string(payload, offset)
        if offset + 8 > len(payload):
            return None
        cx, cz = struct.unpack_from(">ii", payload, offset)
        return world, path, cx, cz
    except (ValueError, UnicodeDecodeError):
        return None


async def read_frame(reader: asyncio.StreamReader):
    prefix = bytearray()
    length = 0
    shift = 0
    while shift < 35:
        byte = (await reader.readexactly(1))[0]
        prefix.append(byte)
        length |= (byte & 0x7F) << shift
        if byte < 0x80:
            break
        shift += 7
    else:
        raise ValueError("frame length VarInt too long")
    payload = await reader.readexactly(length)
    return bytes(prefix) + payload, payload


class Proxy:
    def __init__(self, args):
        self.args = args
        self.armed = False
        self.first = None
        self.source_region = Path(args.source_region)
        self.destination_region = Path(args.destination_region)
        self.source_baseline = 0
        self.destination_baseline = 0

    def load_gate(self):
        if self.armed or not self.args.gate_file:
            return
        gate = Path(self.args.gate_file)
        if not gate.exists():
            return
        config = json.loads(gate.read_text(encoding="utf-8-sig"))
        self.source_baseline = config["source_mtime_ns"]
        self.destination_baseline = config["destination_mtime_ns"]
        self.armed = True
        print("Armed deterministic chunk-write cut", flush=True)

    async def wait_for_persist(self, region: Path, baseline: int):
        deadline = time.monotonic() + self.args.persist_timeout
        while time.monotonic() < deadline:
            try:
                if region.stat().st_mtime_ns > baseline:
                    return region.stat().st_mtime_ns
            except FileNotFoundError:
                pass
            await asyncio.sleep(0.001)
        raise TimeoutError(f"Master did not update {region} after forwarding first chunk write")

    async def kill_backend(self, first, dropped):
        backend_pid = self.args.backend_pid
        if self.args.backend_pid_file and Path(self.args.backend_pid_file).exists():
            backend_pid = int(Path(self.args.backend_pid_file).read_text(encoding="ascii").strip())
        if backend_pid <= 0:
            raise RuntimeError("backend PID was not provided before the crash cut")
        event = {
            "first_persisted": first,
            "second_dropped": dropped,
            "backend_pid": backend_pid,
            "utc": time.time(),
        }
        marker = Path(self.args.marker_file)
        marker.write_text(json.dumps(event, indent=2), encoding="utf-8")
        print(json.dumps(event), flush=True)
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        completed = subprocess.run(
            ["taskkill.exe", "/PID", str(backend_pid), "/F"],
            capture_output=True,
            text=True,
            creationflags=creation_flags,
        )
        if completed.returncode != 0:
            raise RuntimeError(f"taskkill failed: {completed.stdout} {completed.stderr}")
        print("Backend process terminated before the second write was forwarded", flush=True)
        os._exit(0)

    async def handle(self, client_reader, client_writer):
        peer = client_writer.get_extra_info("peername")
        try:
            master_reader, master_writer = await asyncio.open_connection(self.args.master_host, self.args.master_port)
        except Exception as exc:
            print(f"Could not connect to Master for {peer}: {exc}", flush=True)
            client_writer.close()
            await client_writer.wait_closed()
            return

        async def client_to_master():
            while True:
                frame, payload = await read_frame(client_reader)
                self.load_gate()
                parsed = chunk_write(payload) if self.armed else None
                target = None
                if parsed and parsed[0] == self.args.world and parsed[1] == "region":
                    if (parsed[2], parsed[3]) == (self.args.source_cx, self.args.cz):
                        target = "source"
                    elif (parsed[2], parsed[3]) == (self.args.destination_cx, self.args.cz):
                        target = "destination"

                if target and self.first is not None:
                    await self.kill_backend(self.first, {"chunk": target, "cx": parsed[2], "cz": parsed[3]})
                    return

                master_writer.write(frame)
                await master_writer.drain()

                if target and self.first is None:
                    region = self.source_region if target == "source" else self.destination_region
                    baseline = self.source_baseline if target == "source" else self.destination_baseline
                    mtime = await self.wait_for_persist(region, baseline)
                    self.first = {"chunk": target, "cx": parsed[2], "cz": parsed[3], "region_mtime_ns": mtime}
                    print(f"First chunk write persisted: {self.first}", flush=True)

        async def master_to_client():
            while True:
                data = await master_reader.read(65536)
                if not data:
                    return
                client_writer.write(data)
                await client_writer.drain()

        tasks = [asyncio.create_task(client_to_master()), asyncio.create_task(master_to_client())]
        try:
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
            for task in done:
                exception = task.exception()
                if exception and not isinstance(exception, asyncio.IncompleteReadError):
                    print(f"Relay ended for {peer}: {exception}", flush=True)
        finally:
            master_writer.close()
            client_writer.close()
            await asyncio.gather(master_writer.wait_closed(), client_writer.wait_closed(), return_exceptions=True)


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, default=26498)
    parser.add_argument("--master-host", default="127.0.0.1")
    parser.add_argument("--master-port", type=int, default=26499)
    parser.add_argument("--world", required=True)
    parser.add_argument("--source-cx", type=int, required=True)
    parser.add_argument("--destination-cx", type=int, required=True)
    parser.add_argument("--cz", type=int, required=True)
    parser.add_argument("--source-region", required=True)
    parser.add_argument("--destination-region", required=True)
    parser.add_argument("--gate-file", required=True)
    parser.add_argument("--marker-file", required=True)
    parser.add_argument("--backend-pid", type=int, required=True)
    parser.add_argument("--backend-pid-file")
    parser.add_argument("--persist-timeout", type=float, default=30.0)
    args = parser.parse_args()
    proxy = Proxy(args)
    server = await asyncio.start_server(proxy.handle, args.listen_host, args.listen_port)
    print(f"Listening on {args.listen_host}:{args.listen_port}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
