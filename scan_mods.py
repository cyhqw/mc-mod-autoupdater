#!/usr/bin/env python3
"""
scan_mods.py — 扫描 mods/ 目录，生成符合 Modrinth 整合包格式的 modrinth.index.json

用法:
  python scan_mods.py
  python scan_mods.py --mods-dir mods --output modrinth.index.json
  python scan_mods.py --mc-version 1.20.1 --modpack-name "My Pack" --modpack-version 1.0
  python scan_mods.py --loader fabric --loader-version 0.15.11
  python scan_mods.py --include-disabled
  python scan_mods.py --interactive            # 交互式 CLI

要求: Python 3.8+ 标准库（无需 pip install）

工作流程:
  1. 扫描 --mods-dir 下的所有 .jar 文件（默认递归子目录）
  2. 对每个 jar 计算 SHA1 和 SHA512
  3. 通过 Modrinth API 用 SHA1 反查该文件对应的 version 信息
     (GET /version_file/{sha1}?algorithm=sha1)
  4. 若 SHA1 反查失败，回退到按文件名在 Modrinth 上搜索：
     - GET /version_file/{filename}?algorithm=sha1 是不行的，filename 反查不存在
     - 改为 GET /search?query=...&facets=[["project_type:mod"]]，找到候选 project
     - 然后遍历 project 的 version 列表找文件名匹配的，确认该 mod 在 Modrinth
     - 此时 reason 标记为 "on_modrinth_but_different_jar"
  5. 写入 modrinth.index.json
  6. 未在 Modrinth 上找到的 mod 写入 missing.txt（带详细 reason）

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
USER_AGENT = "mc-mod-autoupdater-scanner/0.3.0 (https://github.com/cyhqw/mc-mod-autoupdater)"
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


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


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


def search_modrinth(query: str, limit: int = 5) -> List[Dict[str, Any]]:
    """搜索 Modrinth，返回 hits 列表。"""
    facets_json = '[["project_type:mod"]]'
    params = urllib.parse.urlencode({
        "limit": str(limit),
        "query": query,
        "facets": facets_json,
    })
    url = f"{MODRINTH_API}/search?{params}"
    data = http_get_json(url)
    if data is None:
        return []
    return data.get("hits", []) or []


def get_project_versions(slug_or_id: str, game_version: Optional[str] = None) -> List[Dict[str, Any]]:
    """获取 project 的 version 列表。"""
    url = f"{MODRINTH_API}/project/{urllib.parse.quote(slug_or_id)}/version"
    if game_version:
        gv_json = json.dumps([game_version])
        url += f"?game_versions={urllib.parse.quote(gv_json)}"
    data = http_get_json(url)
    if data is None:
        return []
    return data or []


def find_file_in_version(version: Dict[str, Any], sha1_hash: str) -> Optional[Dict[str, Any]]:
    """在 version JSON 的 files 数组中找到匹配 SHA1 的文件条目。"""
    for f in version.get("files", []):
        hashes = f.get("hashes", {}) or {}
        if hashes.get("sha1", "").lower() == sha1_hash.lower():
            return f
    return None


def extract_search_query(filename: str) -> str:
    """
    从 jar 文件名提取用于搜索的关键词。
    规则：
      - 去掉 [xxx] 前缀（中文标注）
      - 去掉 .jar / .jar.disabled 后缀
      - 去掉版本号部分（-1.20.1、-mc1.20.1-0.5.3 等）
      - 去掉 loader 标识（-forge、-fabric）
      - 把下划线/空格作为词分隔
    """
    name = filename
    # 去 [xxx] 前缀
    while name.startswith("["):
        end = name.find("]")
        if end < 0:
            break
        name = name[end + 1:]
    name = name.strip()
    # 去后缀
    for suffix in (".jar.disabled", ".jar"):
        if name.lower().endswith(suffix):
            name = name[: -len(suffix)]
            break
    # 按常见分隔符切，过滤版本号和 loader 标识
    import re
    tokens = re.split(r"[-_\s]+", name)
    keywords = []
    stop_patterns = (
        r"^\d+(\.\d+)*$",          # 纯版本号
        r"^v?\d",                    # v 开头或数字开头
        r"^(forge|fabric|quilt|neoforge|neoforge|universal|all|client|server|api|core|lib|library)$",
        r"^mc\d",                    # mc1.20.1
        r"^\d+pack",                 # 0Pack2Reload 这种
        r"^\d+$",                    # 纯数字
        r"^build$", r"^beta$", r"^alpha$", r"^release$", r"^snapshot$",
        r"^hotfix$", r"^fix$", r"^patch$",
    )
    for tok in tokens:
        tok = tok.strip()
        if not tok:
            continue
        if any(re.match(p, tok, re.IGNORECASE) for p in stop_patterns):
            continue
        keywords.append(tok)
    # 如果一个 keyword 都没拿到，回退用全名（去后缀）
    if not keywords:
        return name
    return " ".join(keywords[:3])  # 最多用前 3 个


def find_matching_version_by_filename(
    filename: str,
    game_version: str,
    candidates: List[Dict[str, Any]],
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    在候选 project 列表中，找到某个 version 的 file.filename 与传入 filename 完全匹配的条目。
    返回 (version, file) 或 (None, None)。
    """
    target = filename.lower()
    for cand in candidates:
        slug = cand.get("slug") or cand.get("project_id")
        if not slug:
            continue
        try:
            versions = get_project_versions(slug, game_version)
        except Exception:
            continue
        for v in versions:
            for f in v.get("files", []):
                if (f.get("filename") or "").lower() == target:
                    return v, f
        time.sleep(0.1)  # 友善 rate limit
    return None, None


# ---------------------------------------------------------------------------
# Main build entry logic
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
    game_version: str,
    enable_fallback_search: bool,
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    为单个 jar 构建 Modrinth file 条目。
    返回 (entry, missing_info)；entry 为 None 表示未找到。

    策略：
      1. SHA1 反查 Modrinth version_file → 命中即用
      2. 回退：按文件名提取关键词搜索 Modrinth，找到候选 project 后查 version 列表，
         若有文件名完全匹配 → 标记 on_modrinth_but_different_jar（不写入 modrinth.index.json，
         因为 SHA1 不匹配无法校验；但 missing_info.reason 更精确）
      3. 仍无 → not_found_on_modrinth
    """
    try:
        rel = jar.relative_to(mods_dir.parent)
    except ValueError:
        rel = Path("mods") / jar.name
    path_str = str(rel).replace("\\", "/")

    size = jar.stat().st_size
    sha1_hash = sha1(jar)
    sha512_hash = sha512(jar)

    # 1. SHA1 反查
    version = query_modrinth_by_sha1(sha1_hash)
    if version is not None:
        matched = find_file_in_version(version, sha1_hash)
        if matched is not None:
            download_url = matched.get("url") or ""
            if download_url:
                file_size = matched.get("size") or size
                entry = {
                    "path": path_str,
                    "hashes": {"sha1": sha1_hash, "sha512": sha512_hash},
                    "downloads": [download_url],
                    "fileSize": int(file_size),
                    "env": {"client": client_env, "server": server_env},
                }
                return entry, None
            return None, {
                "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
                "reason": "no_download_url",
                "version_id": version.get("id"), "project_id": version.get("project_id"),
            }
        # SHA1 命中 version 但 files 里没有匹配条目（罕见）
        return None, {
            "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
            "reason": "sha1_not_in_version_files",
            "version_id": version.get("id"), "project_id": version.get("project_id"),
        }

    # 2. 文件名搜索回退
    if enable_fallback_search:
        query = extract_search_query(jar.name)
        if query:
            try:
                candidates = search_modrinth(query, limit=5)
            except Exception:
                candidates = []
            if candidates:
                v_matched, f_matched = find_matching_version_by_filename(
                    jar.name, game_version, candidates)
                if v_matched is not None and f_matched is not None:
                    # 在 Modrinth 上找到了同名文件，但 SHA1 不同
                    return None, {
                        "filename": jar.name, "path": path_str,
                        "sha1_local": sha1_hash,
                        "sha1_modrinth": (f_matched.get("hashes", {}) or {}).get("sha1", ""),
                        "size": size,
                        "reason": "on_modrinth_but_different_jar",
                        "modrinth_project_slug": (candidates[0].get("slug") if candidates else ""),
                        "modrinth_project_id": (candidates[0].get("project_id") if candidates else ""),
                        "modrinth_version_id": v_matched.get("id"),
                        "modrinth_file_url": f_matched.get("url", ""),
                        "search_query_used": query,
                    }
                # 没找到文件名匹配，但项目可能存在 —— 标记 project_exists_but_no_matching_file
                top = candidates[0]
                return None, {
                    "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
                    "reason": "project_exists_but_no_matching_file",
                    "modrinth_project_slug": top.get("slug"),
                    "modrinth_project_id": top.get("project_id"),
                    "modrinth_project_title": top.get("title"),
                    "search_query_used": query,
                }

    # 3. 完全未找到
    return None, {
        "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
        "reason": "not_found_on_modrinth",
    }


# ---------------------------------------------------------------------------
# Report rendering
# ---------------------------------------------------------------------------

def render_missing_table(missing: List[Dict[str, Any]]) -> str:
    if not missing:
        return "(no missing mods)"
    lines = []
    lines.append(f"{'#':>3}  {'filename':<55} {'size':>10}  {'reason'}")
    lines.append("-" * 130)
    for i, m in enumerate(missing, 1):
        size = human_size(m.get("size", 0))
        reason = m.get("reason", "unknown")
        # 给 reason 加上下文
        if reason == "on_modrinth_but_different_jar":
            extra = f" (project={m.get('modrinth_project_slug')})"
            reason_display = reason + extra
        elif reason == "project_exists_but_no_matching_file":
            extra = f" (project={m.get('modrinth_project_slug')} \"{m.get('modrinth_project_title')}\")"
            reason_display = reason + extra
        else:
            reason_display = reason
        lines.append(f"{i:>3}  {m.get('filename',''):<55} {size:>10}  {reason_display}")
    return "\n".join(lines)


def reason_summary(reason: str) -> str:
    return {
        "not_found_on_modrinth": "Modrinth 上完全没有此 mod（搜索 0 命中）",
        "project_exists_but_no_matching_file": "Modrinth 上有此 mod 项目，但没有任何 version 的文件名与本地 jar 匹配（可能是不同 loader / 版本 / 分支）",
        "on_modrinth_but_different_jar": "Modrinth 上有同名文件，但 SHA1 与本地 jar 不同（本地 jar 可能来自 CurseForge 或被修改过）",
        "sha1_not_in_version_files": "SHA1 命中了 version 但 files 数组里没有匹配条目（罕见，Modrinth API 数据异常）",
        "no_download_url": "version 响应中匹配的文件条目缺少 url 字段（罕见）",
        "exception": "网络/解析异常",
    }.get(reason, reason)


# ---------------------------------------------------------------------------
# Main non-interactive flow
# ---------------------------------------------------------------------------

def run_scan(args: argparse.Namespace) -> int:
    mods_dir = Path(args.mods_dir).resolve()
    if not mods_dir.is_dir():
        print(f"[ERROR] mods 目录不存在: {mods_dir}", file=sys.stderr)
        return 1

    print(f"[INFO] mods 目录: {mods_dir}")
    print(f"[INFO] 递归: {not args.no_recursive}，包含 disabled: {args.include_disabled}")
    print(f"[INFO] 文件名搜索回退: {args.enable_fallback_search}")

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
        print(f"{prefix} {jar.name} ({human_size(size)}) — ", end="", flush=True)

        try:
            entry, miss = build_file_entry(
                jar, mods_dir, args.client_env, args.server_env,
                args.mc_version, args.enable_fallback_search,
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
            print(f"FOUND  url={entry['downloads'][0][:80]}...")
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
            f.write("# 格式: filename <TAB> sha1 <TAB> path <TAB> reason <TAB> extra\n\n")
            for m in missing:
                extra = ""
                if m.get("reason") == "on_modrinth_but_different_jar":
                    extra = f"modrinth_slug={m.get('modrinth_project_slug')} modrinth_url={m.get('modrinth_file_url')}"
                elif m.get("reason") == "project_exists_but_no_matching_file":
                    extra = f"modrinth_slug={m.get('modrinth_project_slug')} title=\"{m.get('modrinth_project_title')}\""
                f.write(
                    f"{m.get('filename', '')}\t"
                    f"{m.get('sha1') or m.get('sha1_local', '')}\t"
                    f"{m.get('path', '')}\t"
                    f"{m.get('reason', '')}\t"
                    f"{extra}\n"
                )
        print(f"[INFO] 未找到列表已写入 {missing_path.resolve()}")

    if missing:
        print()
        print("=== 未找到明细 ===")
        print(render_missing_table(missing))
        print()
        print("=== reason 含义 ===")
        seen_reasons = sorted({m.get("reason", "unknown") for m in missing})
        for r in seen_reasons:
            print(f"  {r}: {reason_summary(r)}")

    if missing:
        return 2
    return 0


# ---------------------------------------------------------------------------
# Interactive CLI
# ---------------------------------------------------------------------------

def interactive_cli() -> int:
    """交互式命令行界面。"""
    print("=" * 70)
    print("  MC Mod Auto-Updater — scan_mods.py 交互式向导")
    print("=" * 70)
    print()

    # 1. mods 目录
    default_mods = "mods"
    mods_dir_input = prompt(f"mods 目录路径 (回车=默认 {default_mods}): ", default_mods).strip()
    mods_dir = Path(mods_dir_input).resolve()
    if not mods_dir.is_dir():
        print(f"[ERROR] 目录不存在: {mods_dir}", file=sys.stderr)
        return 1
    print(f"  → 已选择: {mods_dir}")
    print()

    # 2. 输出文件
    default_out = "modrinth.index.json"
    out_input = prompt(f"输出文件路径 (回车=默认 {default_out}): ", default_out).strip()
    output_path = Path(out_input)
    print(f"  → 已选择: {output_path}")
    print()

    # 3. 整合包元信息
    modpack_name = prompt("整合包名称 (回车=My Modpack): ", "My Modpack").strip() or "My Modpack"
    modpack_version = prompt("整合包版本号 (回车=1.0): ", "1.0").strip() or "1.0"
    mc_version = prompt("Minecraft 版本 (回车=1.20.1): ", "1.20.1").strip() or "1.20.1"
    print()

    # 4. 加载器
    print("加载器选项:")
    print("  1) Fabric")
    print("  2) Forge")
    print("  3) Quilt")
    print("  4) NeoForge")
    print("  5) 不指定")
    loader_choice = prompt("选择 (1-5, 回车=5): ", "5").strip() or "5"
    loader = None
    loader_version = None
    loader_map = {"1": "fabric", "2": "forge", "3": "quilt", "4": "neoforge"}
    if loader_choice in loader_map:
        loader = loader_map[loader_choice]
        loader_version = prompt(f"{loader} 版本号 (回车=不指定): ", "").strip()
        if not loader_version:
            loader = None
    print()

    # 5. 扫描选项
    print("扫描选项:")
    recursive = yes_no("递归扫描子目录? (Y/n): ", default=True)
    include_disabled = yes_no("包含 .jar.disabled 文件? (y/N): ", default=False)
    fallback_search = yes_no("SHA1 反查失败时启用文件名搜索回退? (Y/n, 更慢但 reason 更精确): ", default=True)
    print()

    # 6. env
    print("文件 env 字段:")
    print("  client/server 取值: required / optional / unsupported")
    client_env = prompt("env.client (回车=required): ", "required").strip() or "required"
    server_env = prompt("env.server (回车=optional): ", "optional").strip() or "optional"
    print()

    # 7. 确认
    print("=" * 70)
    print("配置确认:")
    print(f"  mods 目录:           {mods_dir}")
    print(f"  输出文件:            {output_path}")
    print(f"  整合包名称:          {modpack_name}")
    print(f"  整合包版本:          {modpack_version}")
    print(f"  Minecraft 版本:      {mc_version}")
    if loader:
        print(f"  加载器:              {loader} {loader_version}")
    else:
        print(f"  加载器:              (不指定)")
    print(f"  递归扫描:            {recursive}")
    print(f"  包含 disabled:       {include_disabled}")
    print(f"  文件名搜索回退:      {fallback_search}")
    print(f"  env.client:          {client_env}")
    print(f"  env.server:          {server_env}")
    print("=" * 70)
    if not yes_no("开始扫描? (Y/n): ", default=True):
        print("已取消。")
        return 0

    # 构造 args 复用 run_scan
    args = argparse.Namespace(
        mods_dir=str(mods_dir),
        output=str(output_path),
        modpack_name=modpack_name,
        modpack_version=modpack_version,
        mc_version=mc_version,
        loader=loader,
        loader_version=loader_version,
        client_env=client_env,
        server_env=server_env,
        include_disabled=include_disabled,
        no_recursive=not recursive,
        missing_output="missing.txt",
        enable_fallback_search=fallback_search,
    )
    return run_scan(args)


def prompt(message: str, default: str = "") -> str:
    """带默认值的输入。"""
    try:
        s = input(message)
    except EOFError:
        return default
    return s if s else default


def yes_no(message: str, default: bool = True) -> bool:
    """Y/n 提示。"""
    hint = "(Y/n)" if default else "(y/N)"
    try:
        s = input(message + " " + hint + " ").strip().lower()
    except EOFError:
        return default
    if not s:
        return default
    return s.startswith("y")


# ---------------------------------------------------------------------------
# argparse main
# ---------------------------------------------------------------------------

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
        "--no-fallback-search", dest="enable_fallback_search",
        action="store_false", default=True,
        help="SHA1 反查失败时不启用文件名搜索回退（更快，但 reason 只能笼统报 not_found_on_modrinth）",
    )
    parser.add_argument(
        "--missing-output", default="missing.txt",
        help="未找到的 mod 列表输出文件（默认: missing.txt；为空则不写）",
    )
    parser.add_argument(
        "--interactive", "-i", action="store_true",
        help="进入交互式 CLI 向导（忽略其它参数）",
    )
    args = parser.parse_args()

    if args.interactive:
        return interactive_cli()

    if bool(args.loader) != bool(args.loader_version):
        parser.error("--loader 和 --loader-version 必须同时指定或同时省略")

    return run_scan(args)


if __name__ == "__main__":
    sys.exit(main())
