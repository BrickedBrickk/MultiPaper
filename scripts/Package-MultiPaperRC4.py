#!/usr/bin/env python3
"""Build and verify a deterministic, secret-file-free RC4 evaluation ZIP."""
import argparse
import hashlib
import re
import stat
import zipfile
from pathlib import Path

EXPECTED = {
    "multipaper-paperclip-26.3-RC4.jar": "B32344B9D1A6954E300FFD66F6B6CEE1D3E27FCD36D377DA6D95CAA1E67AC098",
    "multipaper-master-3.0.0.jar": "E122A819950C50FF504CDC6600D6E5C4970CD9D25F45AC19ED974F9D194134B7",
}
FORBIDDEN_SUFFIXES = {".pem", ".key", ".env", ".rcon", ".p12", ".pfx"}
FIXED_TIME = (2026, 1, 1, 0, 0, 0)
REQUIRED_FILES = {
    "README.txt", "LICENSE.txt", "SHA256SUMS.txt", "PACKAGE-SHA256SUMS.txt",
    "COMPATIBILITY-AND-VALIDATION.md", "CURRENT-CANDIDATE-VALIDATION.md",
    "INSTALL-CRAFTY.txt", "INSTALL-PLUGIN-STACK.md", "OFFICIAL-PLUGIN-ARTIFACTS.md",
    "RELEASE-NOTES.txt", "monitoring/README.md", "monitoring/MANUAL-CRAFTY-TOPOLOGY.md",
    "monitoring/NETWORK-ROUTES.md", "monitoring/BACKUP-RESTORE.md", "monitoring/BETA-TEST-PLAN.md",
    "monitoring/config.pc1.example.json", "monitoring/config.pc2.example.json",
    "monitoring/templates/server.properties.example", "monitoring/templates/spigot.yml.fragment",
    "monitoring/templates/multipaper.yml.fragment", "monitoring/templates/geyser-standalone.fragment.yml",
    "scripts/multipaper-beta-monitor.py", "scripts/Measure-BetaMonitorOverhead.py", "scripts/tests/test_beta_monitor.py",
}
SECRET_PATTERNS = [
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\b(?:ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b"),
    re.compile(rb"(?im)^\s*rcon\.password\s*=\s*(?!<|YOUR_|CHANGE_ME|\$\{)[^\s#]+"),
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def manifest(stage: Path) -> list[tuple[str, str]]:
    files = sorted((p for p in stage.rglob("*") if p.is_file()), key=lambda p: p.relative_to(stage).as_posix())
    rels = [p.relative_to(stage).as_posix() for p in files]
    for rel in rels:
        p = Path(rel)
        if any(part.lower() in {"__pycache__", ".git", "logs"} for part in p.parts):
            raise ValueError(f"refusing generated cache/raw log in package: {rel}")
        if p.suffix.lower() in FORBIDDEN_SUFFIXES or p.name.lower() in {"id_rsa", "id_ed25519", "known_hosts"}:
            raise ValueError(f"refusing credential/key file in package: {rel}")
    jars = {p.name: sha256(p) for p in files if p.suffix.lower() == ".jar"}
    if set(jars) != set(EXPECTED):
        raise ValueError(f"package must contain exactly the Master and Paperclip JARs; found {sorted(jars)}")
    for name, expected in EXPECTED.items():
        if jars[name] != expected:
            raise ValueError(f"{name} hash mismatch: expected {expected}, got {jars[name]}")
    return [(sha256(p), p.relative_to(stage).as_posix()) for p in files if p.name not in {"PACKAGE-SHA256SUMS.txt", "SHA256SUMS.txt"}]


def write_manifests(stage: Path, entries: list[tuple[str, str]]) -> None:
    lines = [f"{EXPECTED[name]}  {name}" for name in sorted(EXPECTED)]
    (stage / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    package = [f"{digest}  {path}" for digest, path in entries]
    (stage / "PACKAGE-SHA256SUMS.txt").write_text("\n".join(package) + "\n", encoding="utf-8", newline="\n")


def validate_content(stage: Path) -> None:
    rels = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file()}
    missing = sorted(REQUIRED_FILES - rels)
    if missing:
        raise ValueError(f"missing release payloads: {missing}")
    for path in stage.rglob("*"):
        if not path.is_file(): continue
        rel = path.relative_to(stage).as_posix()
        data = path.read_bytes()
        if any(pattern.search(data) for pattern in SECRET_PATTERNS):
            raise ValueError(f"credential-like value detected in {rel}")
        if path.suffix.lower() in {".md", ".txt"}:
            text = data.decode("utf-8", "replace")
            for target in re.findall(r"\]\(([^)]+)\)", text):
                if target.startswith(("https://", "http://", "mailto:", "#")): continue
                target_path = target.split("#", 1)[0].split("?", 1)[0]
                if target_path and not (stage / target_path).exists():
                    raise ValueError(f"broken relative link in {rel}: {target}")


def audit_zip(stage: Path, archive_path: Path) -> int:
    with zipfile.ZipFile(archive_path, "r") as z:
        infos = z.infolist()
        names = [i.filename for i in infos]
        if len(names) != len(set(names)):
            raise ValueError("ZIP contains duplicate member names")
        if names != sorted(names):
            raise ValueError("ZIP members are not in deterministic sorted order")
        expected_files = {p.relative_to(stage).as_posix(): sha256(p) for p in stage.rglob("*") if p.is_file()}
        actual = {n: hashlib.sha256(z.read(n)).hexdigest().upper() for n in names}
        if actual != expected_files:
            missing = sorted(set(expected_files) - set(actual))
            extra = sorted(set(actual) - set(expected_files))
            bad = sorted(n for n in set(actual) & set(expected_files) if actual[n] != expected_files[n])
            raise ValueError(f"ZIP payload mismatch; missing={missing}, extra={extra}, mismatched={bad}")
        for info in infos:
            info_time = info.date_time
            if info_time != FIXED_TIME:
                raise ValueError(f"non-deterministic timestamp for {info.filename}: {info_time}")
    return len(infos)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--stage", required=True, type=Path)
    ap.add_argument("--zip", required=True, type=Path)
    args = ap.parse_args()
    stage = args.stage.resolve()
    output = args.zip.resolve()
    if output.exists():
        raise SystemExit(f"refusing to overwrite existing archive: {output}")
    if not stage.is_dir():
        raise SystemExit(f"staging directory does not exist: {stage}")
    validate_content(stage)
    entries = manifest(stage)
    write_manifests(stage, entries)
    all_files = sorted((p for p in stage.rglob("*") if p.is_file()), key=lambda p: p.relative_to(stage).as_posix())
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in all_files:
            rel = p.relative_to(stage).as_posix()
            info = zipfile.ZipInfo(rel, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_STORED if p.suffix.lower() == ".jar" else zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            z.writestr(info, p.read_bytes(), compress_type=info.compress_type, compresslevel=9)
    count = audit_zip(stage, output)
    sidecar = output.with_suffix(output.suffix + ".sha256")
    sidecar.write_text(f"{sha256(output)}  {output.name}\n", encoding="ascii", newline="\n")
    print(f"PASS: {count} deterministic ZIP payloads; SHA-256={sha256(output)}; {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
