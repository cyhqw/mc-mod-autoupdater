#!/usr/bin/env python3
"""
scan_mods.py — 扫描 mods/ 目录，生成符合 Modrinth 整合包格式的 modrinth.index.json

用法:
  python scan_mods.py
  python scan_mods.py --mods-dir mods --output modrinth.index.json
  python scan_mods.py --mc-version 1.20.1 --modpack-name "My Pack" --modpack-version 1.0
  python scan_mods.py --loader fabric --loader-version 0.15.11
  python scan_mods.py --include-disabled

要求: Python 3.8+ 标准库（无需 pip install）

工作流程:
  1. 扫描 --mods-dir 下的所有 .jar 文件（默认递归子目录）
  2. 对每个 jar 计算 SHA1 和 SHA512
  3. 通过 Modrinth API 用 SHA1 反查该文件对应的 version 信息
     (GET /version_file/{sha1}?algorithm=sha1)
  4. 从 version 响应中提取匹配文件的 download URL
  5. 写入 modrinth.index.json
  6. 未在 Modrinth 上找到的 mod 写入 missing.txt

退出码:
  0 — 全部成功
  1 — 严重错误（mods 目录不存在等）
  2 — 部分文件未在 Modrinth 上找到（但 modrinth.index.json 已生成）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

MODRINTH_API = "https://api.modrinth.com/v2"
USER_AGENT = "mc-mod-autoupdater-scanner/0.2.0 (https://github.com/cyhqw/mc-mod-autoupdater)"
HTTP_TIMEOUT = 20  # seconds
MAX_RETRIES = 2


# ---------------------------------------------------------------------------
# IO / hashing helpers
# ---------------------------------------------------------------------------

def sha_hash(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha1(path: Path) -> str:
    return sha_hash(path, "sha1")


def sha512(path: Path) -> str:
    return sha_hash(path, "sha512")


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def http_get_json(url: str, timeout: int = HTTP_TIMEOUT) -> Optional[Dict[str, Any]]:
    req = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    last_err: Optional[Exception] = None
    for attempt in range(MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8")
                return json.loads(body)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            last_err = e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            last_err = e
        if attempt < MAX_RETRIES:
            time.sleep(0.5 * (2 ** attempt))
    raise RuntimeError(f"HTTP GET {url} failed after {MAX_RETRIES + 1} attempts: {last_err}")


def query_modrinth_by_sha1(sha1_hash: str) -> Optional[Dict[str, Any]]:
    """通过 SHA1 反查 Modrinth version_file，返回 version JSON 或 None（404）。"""
    url = (
        f"{MODRINTH_API}/version_file/"
        f"{urllib.parse.quote(sha1_hash)}?algorithm=sha1"
    )
    return http_get_json(url)


def find_file_in_version(version: Dict[str, Any], sha1_hash: str) -> Optional[Dict[str, Any]]:
    """在 version JSON 的 files 数组中找到匹配 SHA1 的文件条目。"""
    for f in version.get("files", []):
        hashes = f.get("hashes", {}) or {}
        if hashes.get("sha1", "").lower() == sha1_hash.lower():
            return f
    return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def collect_jars(mods_dir: Path, include_disabled: bool, recursive: bool) -> List[Path]:
    """收集所有 .jar 文件（可选包含 .jar.disabled）。"""
    jars: List[Path] = []
    if recursive:
        iterator = mods_dir.rglob("*")
    else:
        iterator = mods_dir.glob("*")
    for p in iterator:
        if not p.is_file():
            continue
        name = p.name.lower()
        if name.endswith(".jar"):
            jars.append(p)
        elif include_disabled and name.endswith(".jar.disabled"):
            jars.append(p)
    return sorted(jars)


def build_file_entry(
    jar: Path,
    mods_dir: Path,
    client_env: str,
    server_env: str,
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    为单个 jar 构建 Modrinth file 条目。
    返回 (entry, missing_info)；entry 为 None 表示未找到。
    """
    # path：相对 mods_dir 的父目录，保留 "mods/" 前缀
    # 例如 mods_dir = /opt/server/mods，jar = /opt/server/mods/sodium.jar
    # 则 path = mods/sodium.jar
    try:
        rel = jar.relative_to(mods_dir.parent)
    except ValueError:
        # jar 不在 mods_dir 下，使用 filename 兜底
        rel = Path("mods") / jar.name
    path_str = str(rel).replace("\\", "/")

    size = jar.stat().st_size
    sha1_hash = sha1(jar)
    sha512_hash = sha512(jar)

    version = query_modrinth_by_sha1(sha1_hash)
    if version is None:
        return None, {
            "filename": jar.name,
            "path": path_str,
            "sha1": sha1_hash,
            "size": size,
            "reason": "not_found_on_modrinth",
        }

    matched = find_file_in_version(version, sha1_hash)
    if matched is None:
        return None, {
            "filename": jar.name,
            "path": path_str,
            "sha1": sha1_hash,
            "size": size,
            "reason": "sha1_not_in_version_files",
            "version_id": version.get("id"),
            "project_id": version.get("project_id"),
        }

    download_url = matched.get("url") or ""
    if not download_url:
        return None, {
            "filename": jar.name,
            "path": path_str,
            "sha1": sha1_hash,
            "size": size,
            "reason": "no_download_url",
            "version_id": version.get("id"),
            "project_id": version.get("project_id"),
        }

    # Modrinth 返回的 size 可能为 None；用本地文件大小兜底
    file_size = matched.get("size") or size

    entry = {
        "path": path_str,
        "hashes": {
            "sha1": sha1_hash,
            "sha512": sha512_hash,
        },
        "downloads": [download_url],
        "fileSize": int(file_size),
        "env": {
            "client": client_env,
            "server": server_env,
        },
    }
    return entry, None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="扫描 mods/ 目录生成 Modrinth 整合包格式的 modrinth.index.json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--mods-dir", default="mods",
        help="mods 目录路径（默认: mods，相对当前工作目录）",
    )
    parser.add_argument(
        "--output", "-o", default="modrinth.index.json",
        help="输出文件路径（默认: modrinth.index.json）",
    )
    parser.add_argument(
        "--mc-version", default="1.20.1",
        help="Minecraft 版本，写入 dependencies.minecraft（默认: 1.20.1）",
    )
    parser.add_argument(
        "--modpack-name", default="My Modpack",
        help="整合包名称（默认: My Modpack）",
    )
    parser.add_argument(
        "--modpack-version", default="1.0",
        help="整合包版本号，写入 versionId（默认: 1.0）",
    )
    parser.add_argument(
        "--loader",
        choices=["fabric", "quilt", "forge", "neoforge"],
        help="加载器类型，与 --loader-version 一起写入 dependencies",
    )
    parser.add_argument(
        "--loader-version",
        help="加载器版本，与 --loader 一起写入 dependencies",
    )
    parser.add_argument(
        "--client-env", default="required",
        choices=["required", "optional", "unsupported"],
        help="env.client 默认值（默认: required）",
    )
    parser.add_argument(
        "--server-env", default="optional",
        choices=["required", "optional", "unsupported"],
        help="env.server 默认值（默认: optional）",
    )
    parser.add_argument(
        "--include-disabled", action="store_true",
        help="同时扫描 .jar.disabled 文件",
    )
    parser.add_argument(
        "--no-recursive", action="store_true",
        help="不递归子目录，仅扫描 mods_dir 顶层",
    )
    parser.add_argument(
        "--missing-output", default="missing.txt",
        help="未找到的 mod 列表输出文件（默认: missing.txt；为空则不写）",
    )
    args = parser.parse_args()

    # 校验 loader / loader-version 必须同时给出
    if bool(args.loader) != bool(args.loader_version):
        parser.error("--loader 和 --loader-version 必须同时指定或同时省略")

    mods_dir = Path(args.mods_dir).resolve()
    if not mods_dir.is_dir():
        print(f"[ERROR] mods 目录不存在: {mods_dir}", file=sys.stderr)
        return 1

    print(f"[INFO] mods 目录: {mods_dir}")
    print(f"[INFO] 递归: {not args.no_recursive}，包含 disabled: {args.include_disabled}")

    jars = collect_jars(mods_dir, args.include_disabled, recursive=not args.no_recursive)
    if not jars:
        print(f"[WARN] {mods_dir} 下没有 .jar 文件", file=sys.stderr)
    else:
        print(f"[INFO] 扫描到 {len(jars)} 个 .jar 文件")

    entries: List[Dict[str, Any]] = []
    missing: List[Dict[str, Any]] = []

    for i, jar in enumerate(jars, 1):
        prefix = f"[{i:>3}/{len(jars)}]"
        size = jar.stat().st_size
        print(f"{prefix} {jar.name} ({size:,} bytes) — ", end="", flush=True)

        try:
            entry, miss = build_file_entry(
                jar, mods_dir, args.client_env, args.server_env,
            )
        except Exception as e:
            print(f"ERROR: {e}")
            missing.append({
                "filename": jar.name,
                "path": str(jar.relative_to(mods_dir.parent)).replace("\\", "/"),
                "reason": f"exception: {e}",
            })
            continue

        if entry is not None:
            project_id = ""
            try:
                # build_file_entry 内部已经查询过 version；为减少 API 调用，
                # 这里再用 sha1 查一次以拿 project_id 用于日志显示。
                sha1_hash = entry["hashes"]["sha1"]
                version = query_modrinth_by_sha1(sha1_hash)
                if version:
                    project_id = version.get("project_id") or ""
            except Exception:
                pass
            print(f"FOUND  project={project_id}  url={entry['downloads'][0][:80]}...")
            entries.append(entry)
        else:
            reason = miss.get("reason", "unknown") if miss else "unknown"
            print(f"NOT FOUND ({reason})")
            if miss:
                missing.append(miss)

    # 组装 modrinth.index.json
    dependencies: Dict[str, str] = {"minecraft": args.mc_version}
    if args.loader and args.loader_version:
        loader_key = {
            "fabric": "fabric-loader",
            "quilt": "quilt-loader",
            "forge": "forge",
            "neoforge": "neoforge",
        }[args.loader]
        dependencies[loader_key] = args.loader_version

    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": args.modpack_version,
        "name": args.modpack_name,
        "files": entries,
        "dependencies": dependencies,
    }

    output_path = Path(args.output)
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print()
    print(f"[OK] 写入 {output_path.resolve()}")
    print(f"     - 已收录: {len(entries)} 个文件")
    print(f"     - 未找到: {len(missing)} 个文件")
    print(f"     - 整合包: {args.modpack_name} v{args.modpack_version}")
    print(f"     - 依赖: {dependencies}")

    if missing and args.missing_output:
        missing_path = Path(args.missing_output)
        with missing_path.open("w", encoding="utf-8") as f:
            f.write("# 未在 Modrinth 上找到的 mod 列表\n")
            f.write("# 格式: filename <TAB> sha1 <TAB> path <TAB> reason\n\n")
            for m in missing:
                f.write(
                    f"{m.get('filename', '')}\t"
                    f"{m.get('sha1', '')}\t"
                    f"{m.get('path', '')}\t"
                    f"{m.get('reason', '')}\n"
                )
        print(f"[INFO] 未找到列表已写入 {missing_path.resolve()}")

    if missing:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
