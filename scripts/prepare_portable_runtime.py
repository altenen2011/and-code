#!/usr/bin/env python3
"""Build offline (portable) on-device rootfs bundles for AndCode.

Each bundle is a prebuilt Alpine rootfs with the REQUIRED runtime packages
pre-installed and the pinned OpenCode binary staged, so phone setup needs no
network at all: the installer extracts the bundle, runs the local
configuration steps, and starts the server.

Layout per Android ABI, written to --output-dir::

    portable-rootfs-<abi>.tar.gz      # the rootfs, with bundle-manifest.json at its root
    portable-rootfs-<abi>.sha256      # hex digest of the tarball (for logs, not verified on device)

The tarball embeds ``bundle-manifest.json`` recording every input version and
hash. The installer compares those pins against the app's own
``local-runtime-manifest.json`` before trusting the bundle, so a stale or
mismatched bundle can never silently provision an old runtime.

Only stdlib is used; network access goes to dl-cdn.alpinelinux.org and the npm
registry. Keep REQUIRED_PACKAGES in sync with
``LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES``.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import sys
import tarfile
import urllib.request
from pathlib import Path

ALPINE_CDN = "https://dl-cdn.alpinelinux.org/alpine"
NPM_REGISTRY = "https://registry.npmjs.org"

# Must match LocalRuntimeInstaller.REQUIRED_RUNTIME_PACKAGES.
REQUIRED_PACKAGES = [
    "bash",
    "git",
    "curl",
    "wget",
    "jq",
    "openssh-client",
    "ripgrep",
    "ca-certificates",
    "libstdc++",
    "android-tools",
    "python3",
    "py3-pillow",
]

ANDROID_TO_ALPINE_ARCH = {
    "arm64-v8a": "aarch64",
    "x86_64": "x86_64",
}

BUNDLE_SCHEMA_VERSION = 1


def fetch(url: str, timeout: int = 120) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "AndCode-portable-builder"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"Download failed (HTTP {response.status}): {url}")
        return response.read()


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def npm_integrity_ok(data: bytes, integrity: str) -> None:
    if not integrity.startswith("sha512-"):
        raise ValueError(f"Unsupported integrity algorithm: {integrity}")
    expected = base64.b64decode(integrity.removeprefix("sha512-"))
    actual = hashlib.sha512(data).digest()
    if actual != expected:
        raise ValueError("npm tarball SHA-512 mismatch")


def fetch_index(branch: str, arch: str) -> tuple[dict[str, dict], str]:
    """Fetch main+community indexes merged; returns (index, source description)."""
    index: dict[str, dict] = {}
    for repo in ("main", "community"):
        url = f"{ALPINE_CDN}/v{branch}/{repo}/{arch}/APKINDEX.tar.gz"
        index.update(parse_apkindex(fetch(url)))
    return index, ALPINE_CDN


def parse_apkindex(data: bytes) -> dict[str, dict]:
    """Parse an APKINDEX.tar.gz into {pkgname: {version, depends, checksum, size}}."""
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        member = tar.getmember("APKINDEX")
        index_text = tar.extractfile(member).read().decode()
    packages: dict[str, dict] = {}
    current: dict = {}
    for line in index_text.splitlines():
        if not line.strip():
            if "P" in current:
                packages[current["P"]] = current
            current = {}
            continue
        tag, _, value = line.partition(":")
        current[tag] = value
    if "P" in current:
        packages[current["P"]] = current
    return packages


def clean_dep_token(token: str) -> str:
    token = token.strip()
    for sep in ("=", "<", ">", "~"):
        token = token.split(sep)[0]
    return token.strip()


def parse_installed_db(rootfs: Path) -> dict[str, str]:
    """Read Alpine's installed DB ({rootfs}/lib/apk/db/installed) as {name: version}."""
    db = rootfs / "lib" / "apk" / "db" / "installed"
    if not db.is_file():
        return {}
    provided: dict[str, str] = {}
    name = version = ""
    for line in db.read_text().splitlines():
        if not line.strip():
            if name:
                provided[name] = version
            name = version = ""
            continue
        tag, _, value = line.partition(":")
        if tag == "P":
            name = value
        elif tag == "V":
            version = value
    if name:
        provided[name] = version
    return provided


def resolve_closure(
    index: dict[str, dict],
    roots: list[str],
    provided: dict[str, str] | None = None,
) -> list[dict]:
    """Depth-first post-order dependency closure over APKINDEX records.

    Packages already present in the minirootfs (``provided``) are recorded with
    ``source: minirootfs`` and never re-downloaded.
    """
    provides: dict[str, str] = {}
    for name, record in index.items():
        for item in record.get("p", "").split():
            # Keep the qualified name (so:…, cmd:…, pc:…): dependency tokens use the same form.
            key = item.split("=")[0].strip()
            if key and key not in provides:
                provides[key] = name
    # Virtuals the minirootfs already satisfies through its own packages resolve to those
    # packages instead of re-downloading their apk split (e.g. /bin/sh from busybox).
    preinstalled_provides: dict[str, str] = {}
    for name, version in (provided or {}).items():
        record = index.get(name)
        if record is None:
            continue
        for item in record.get("p", "").split():
            key = item.split("=")[0].strip()
            if key:
                preinstalled_provides.setdefault(key, name)
    resolved: list[dict] = []
    seen: set[str] = set()
    preinstalled: dict[str, str] = provided or {}

    def visit(name: str) -> None:
        if name in seen:
            return
        if name in preinstalled:
            seen.add(name)
            resolved.append({"P": name, "V": preinstalled[name], "_preinstalled": True})
            return
        provider = preinstalled_provides.get(name)
        if provider is not None:
            seen.add(name)
            seen.add(provider)
            resolved.append({"P": provider, "V": preinstalled[provider], "_preinstalled": True})
            return
        record = index.get(name) or (index.get(provides[name]) if name in provides else None)
        if record is None:
            raise RuntimeError(f"Unresolvable Alpine package: {name}")
        seen.add(record["P"])
        for token in record.get("D", "").split():
            dep = clean_dep_token(token)
            if dep and not dep.startswith("!"):
                visit(dep)
        resolved.append(record)

    for root in roots:
        visit(root)
    return resolved


def apk_filename(record: dict) -> str:
    return f"{record['P']}-{record['V']}.apk"


def apk_sha1(record: dict) -> str:
    checksum = record.get("C", "")
    if not checksum.startswith("Q1"):
        raise RuntimeError(f"No Q1 checksum for package {record['P']}")
    raw = base64.b64decode(checksum[2:])
    if len(raw) != 20:
        raise RuntimeError(f"Unexpected checksum length for package {record['P']}")
    return raw.hex()


def portable_filter(member: tarfile.TarInfo, dest_path: str) -> tarfile.TarInfo:
    """Like tarfile's data filter, but rewrites absolute symlink targets to archive-relative.

    Alpine packages linkBusyBox-style (``/bin/busybox``); inside a rootfs tree those must
    resolve under the tree, not the build host.
    """
    if member.issym() or member.islnk():
        target = member.linkname
        if os.path.isabs(target):
            member.linkname = target.lstrip("/")
    return tarfile.data_filter(member, dest_path)


def extract_apk_data(apk_bytes: bytes, rootfs: Path) -> None:
    """Layer an .apk's data.tar.gz into rootfs (no triggers; those run on device)."""
    with tarfile.open(fileobj=io.BytesIO(apk_bytes), mode="r:gz") as outer:
        data_member = next((m for m in outer.getmembers() if m.name == "data.tar.gz"), None)
        if data_member is None:
            raise RuntimeError("APK has no data.tar.gz")
        data_bytes = outer.extractfile(data_member).read()
    with tarfile.open(fileobj=io.BytesIO(data_bytes), mode="r:gz") as data:
        data.extractall(path=rootfs, filter=portable_filter)


def extract_member(tarball: bytes, member_name: str) -> bytes:
    with tarfile.open(fileobj=io.BytesIO(tarball), mode="r:gz") as tar:
        member = tar.getmember(member_name)
        return tar.extractfile(member).read()


def build_bundle(
    manifest: dict,
    android_abi: str,
    output_dir: Path,
    staging_base: Path,
) -> Path:
    alpine_arch = ANDROID_TO_ALPINE_ARCH[android_abi]
    alpine_version = manifest["alpineVersion"]
    alpine_branch = ".".join(alpine_version.split(".")[:2])
    print(f"[{android_abi}] Alpine {alpine_version} ({alpine_arch})", flush=True)

    arch_record = manifest["architectures"][android_abi]

    # 1. Alpine minirootfs (pinned + verified, same source the online setup uses).
    minirootfs_url = arch_record["alpineUrl"]
    minirootfs = fetch(minirootfs_url)
    if sha256_hex(minirootfs) != arch_record["alpineSha256"]:
        raise RuntimeError("Alpine minirootfs SHA-256 mismatch")
    print(f"[{android_abi}] minirootfs {len(minirootfs) // 1024} KiB verified", flush=True)

    # 2. Extract it first: whatever it already ships (musl, busybox, keys) is authoritative
    # and never re-downloaded, which also sidesteps index/file skew on those packages.
    staging = staging_base / f"portable-staging-{android_abi}"
    if staging.exists():
        import shutil

        shutil.rmtree(staging)
    rootfs = staging / "rootfs"
    rootfs.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(minirootfs), mode="r:gz") as tar:
        tar.extractall(path=rootfs, filter=portable_filter)
    preinstalled = parse_installed_db(rootfs)
    print(f"[{android_abi}] minirootfs ships {len(preinstalled)} packages", flush=True)

    # 3. Resolve + fetch the remaining APK set (online index, recorded hashes).
    repos = ["main", "community"]
    index, _ = fetch_index(alpine_branch, alpine_arch)
    closure = resolve_closure(index, REQUIRED_PACKAGES, preinstalled)
    needed = [r for r in closure if not r.get("_preinstalled")]
    print(f"[{android_abi}] resolved {len(closure)} packages ({len(needed)} to fetch)", flush=True)
    apk_blobs: list[tuple[dict, bytes]] = []
    for record in needed:
        # The CDN index can lag a rebuilt package (same version, new hash). Refresh the index
        # once before giving up so a mid-rollout mirror does not poison the whole build.
        blob = fetch_apk_verified(alpine_branch, alpine_arch, repos, record, index)
        apk_blobs.append((record, blob))
    print(f"[{android_abi}] fetched {sum(len(b) for _, b in apk_blobs) // 1024} KiB of apks", flush=True)
    for record, blob in apk_blobs:
        extract_apk_data(blob, rootfs)

    # 4. Stage the pinned OpenCode binary (npm channel, integrity verified).
    open_code_version = manifest["openCodeVersion"]
    channel = manifest.get("openCodeChannel", "github")
    if channel == "npm":
        tarball_url = arch_record["npmTarballUrl"]
        integrity = arch_record["npmIntegrity"]
        tarball = fetch(tarball_url)
        npm_integrity_ok(tarball, integrity)
        binary = extract_member(tarball, "package/bin/opencode")
    else:
        tarball_url = arch_record["openCodeUrl"]
        tarball = fetch(tarball_url)
        if sha256_hex(tarball) != arch_record["openCodeSha256"]:
            raise RuntimeError("OpenCode tarball SHA-256 mismatch")
        binary = extract_member(tarball, "opencode")
    target = rootfs / "usr" / "local" / "bin" / "opencode"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(binary)
    target.chmod(0o755)
    print(f"[{android_abi}] opencode {open_code_version} staged ({len(binary) // 1024} KiB)", flush=True)

    # 5. Bundle manifest + tarball.
    bundle_manifest = {
        "schemaVersion": BUNDLE_SCHEMA_VERSION,
        "androidAbi": android_abi,
        "alpineVersion": alpine_version,
        "alpineSha256": arch_record["alpineSha256"],
        "openCodeVersion": open_code_version,
        "openCodeChannel": channel,
        "openCodeIntegrity": arch_record.get("npmIntegrity") or arch_record.get("openCodeSha256"),
        "packages": [
            {
                "name": r["P"],
                "version": r["V"],
                "size": int(r["S"]) if "S" in r else 0,
                "sha1": apk_sha1(r) if "C" in r else "",
                "source": "minirootfs" if r.get("_preinstalled") else "apk",
            }
            for r in closure
        ],
    }
    manifest_file = staging / "bundle-manifest.json"
    manifest_file.write_text(json.dumps(bundle_manifest, indent=2))
    out = output_dir / f"portable-rootfs-{android_abi}.tar.gz"
    with tarfile.open(out, mode="w:gz", compresslevel=6) as tar:
        # The manifest goes first so the installer can verify pins from the stream before
        # extracting anything.
        tar.add(manifest_file, arcname="bundle-manifest.json")
        tar.add(rootfs, arcname=".")
    digest = sha256_hex(out.read_bytes())
    (output_dir / f"portable-rootfs-{android_abi}.sha256").write_text(f"{digest}  {out.name}\n")
    print(f"[{android_abi}] bundle {out.name} ({out.stat().st_size // (1024 * 1024)} MiB) sha256={digest[:16]}…", flush=True)
    return out


def fetch_apk_verified(
    branch: str,
    arch: str,
    repos: list[str],
    record: dict,
    index: dict[str, dict],
) -> bytes:
    """Fetch one APK with size + SHA-1 verification, refreshing a lagging index once."""
    try:
        return fetch_apk_once(branch, arch, repos, record)
    except RuntimeError as first:
        refreshed, _ = fetch_index(branch, arch)
        index.update(refreshed)
        fresh = refreshed.get(record["P"])
        if fresh is None or fresh.get("V") != record.get("V"):
            raise first
        try:
            return fetch_apk_once(branch, arch, repos, fresh)
        except RuntimeError:
            raise first


def fetch_apk_once(branch: str, arch: str, repos: list[str], record: dict) -> bytes:
    url = locate_apk(branch, arch, repos, record)
    blob = fetch(url)
    if len(blob) != int(record["S"]):
        raise RuntimeError(f"Size mismatch for {apk_filename(record)}")
    if hashlib.sha1(blob).hexdigest() != apk_sha1(record):
        raise RuntimeError(f"SHA-1 mismatch for {apk_filename(record)}")
    return blob


def locate_apk(branch: str, arch: str, repos: list[str], record: dict) -> str:
    # Repositories are disjoint by package name in practice; probe main then community with a
    # cheap HEAD-equivalent (urllib has no HEAD helper here, so stream one byte via Range).
    name = apk_filename(record)
    for repo in repos:
        url = f"{ALPINE_CDN}/v{branch}/{repo}/{arch}/{name}"
        request = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "AndCode-portable-builder"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status in (200, 206):
                    return url
        except Exception:
            continue
    raise RuntimeError(f"APK not found in any repo: {name}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build portable on-device rootfs bundles.")
    parser.add_argument("--manifest", required=True, help="local-runtime-manifest.json path")
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--abis", default="arm64-v8a,x86_64")
    args = parser.parse_args()

    manifest = json.loads(Path(args.manifest).read_text())
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    import shutil
    import tempfile

    staging_base = Path(tempfile.mkdtemp(prefix="andcode-portable-"))
    try:
        for abi in [a.strip() for a in args.abis.split(",") if a.strip()]:
            if abi not in manifest["architectures"]:
                raise SystemExit(f"ABI {abi} not in manifest")
            build_bundle(manifest, abi, output_dir, staging_base)
        return 0
    finally:
        shutil.rmtree(staging_base, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
