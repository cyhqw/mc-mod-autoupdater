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
  python scan_mods.py --threads 8              # 多线程并行（默认 4）

要求: Python 3.8+ 标准库（无需 pip install）

工作流程:
  1. 扫描 --mods-dir 下的所有 .jar 文件（默认递归子目录）
  2. 对每个 jar 计算 SHA1 和 SHA512
  3. 多线程并行处理每个 jar:
     a) 通过 Modrinth API 用 SHA1 反查 version_file
     b) 若 SHA1 反查失败，回退到按文件名搜索:
        - 从文件名提取关键词，尝试多个查询变体（去空格、加连字符、原始名）
        - 调 /search API 找候选 project
        - 遍历候选 project 的 version 列表:
          * filename 完全匹配 → 用 Modrinth URL+SHA1 (reason=on_modrinth_different_jar_same_filename)
          * slug 含于文件名 + version_number 匹配 → 用 Modrinth URL+SHA1 (reason=on_modrinth_matched_by_name_and_version)
          * 找到 project 但无任何匹配 → missing (reason=project_exists_but_no_matching_file)
          * 完全没找到 → missing (reason=not_found_on_modrinth)
  4. 写入 modrinth.index.json
  5. 未在 Modrinth 上找到的 mod 写入 missing.txt

注意：当 SHA1 不符但名称+版本号对上时，使用 Modrinth 上的 URL 和 SHA1 写入 JSON。
客户端会下载 Modrinth 版本（与本地 jar 内容可能略有差异，但功能等价）。

退出码:
  0 — 全部成功
  1 — 严重错误（mods 目录不存在等）
  2 — 部分文件未在 Modrinth 上找到（但 modrinth.index.json 已生成）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

MODRINTH_API = "https://api.modrinth.com/v2"
USER_AGENT = "mc-mod-autoupdater-scanner/0.4.0 (https://github.com/cyhqw/mc-mod-autoupdater)"
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


# ---------------------------------------------------------------------------
# 文件名解析与查询变体生成
# ---------------------------------------------------------------------------

# 用于从文件名中识别版本号的正则：1.20-1.4.15 / 1.20.1-14.1.20 / mc1.20.1-0.5.3 等
VERSION_RE = re.compile(
    r"(\d+\.\d+(?:\.\d+)?(?:[-_.]\d+)*"  # 主版本号
    r"(?:\+\w+)?"                          # 可选 +xxx 后缀
    r"(?:[-.](?:beta|alpha|rc|hotfix|fix|build)\d*)?)",
    re.IGNORECASE,
)

# 加载器标识
LOADER_TOKENS = {"forge", "fabric", "quilt", "neoforge", "neoforge", "universal", "all", "client", "server"}


def parse_filename(filename: str) -> Dict[str, str]:
    """
    从 jar 文件名解析出 mod 名、版本号、加载器、MC 版本。
    返回 dict: {name, version, mc_version, loader, name_variants}

    解析规则（以 "ExtendedAE-1.20-1.4.15-forge.jar" 为例）:
      - tokens = [ExtendedAE, 1.20, 1.4.15, forge]
      - loader = "forge" (匹配 LOADER_TOKENS)
      - 从前往后找第一个匹配 VERSION_RE 的 token，作为 mc_version = "1.20"
      - 从 mc_version 之后找下一个版本号 token，作为 mod 版本 = "1.4.15"
      - mod name = mc_version 之前的 tokens 拼接 = "ExtendedAE"

    特殊情况:
      - 没有 mc 版本前缀（如 "sodium-0.5.3.jar"）: mc_version="", version="0.5.3"
      - 只有 mc 版本没有 mod 版本（如 "fabric-api-1.20.1.jar"）: version=""
      - 文件名前缀 [中文标注] 会先去掉
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

    # 按 - 或 _ 切分（保留原始顺序）
    tokens = re.split(r"[-_\s]+", name)

    # 加载器
    loader = None
    loader_idx = None
    for i, tok in enumerate(tokens):
        if tok.lower() in LOADER_TOKENS:
            loader = tok.lower()
            loader_idx = i
            break

    # 找所有版本号 token（匹配 VERSION_RE 且含数字）
    version_indices = []
    for i, tok in enumerate(tokens):
        if loader_idx is not None and i == loader_idx:
            continue
        if VERSION_RE.search(tok) and any(c.isdigit() for c in tok):
            version_indices.append(i)

    mc_version = ""
    version_str = ""
    mod_name_end = len(tokens)
    if loader_idx is not None:
        mod_name_end = loader_idx

    if version_indices:
        # 第一个版本号通常是 mc 版本（如 1.20, 1.20.1, mc1.20.1）
        first_vi = version_indices[0]
        mc_version = tokens[first_vi]
        # mod name 在第一个版本号之前
        mod_name_end = min(mod_name_end, first_vi)
        # 如果有第二个版本号，且第二个版本号紧跟在 mc 版本之后（中间无 mod 名 token），它是 mod 版本
        if len(version_indices) >= 2:
            second_vi = version_indices[1]
            # 检查 first_vi 和 second_vi 之间是否有非版本号 token
            # 如果中间没有 token（即 second_vi == first_vi + 1），第二个就是 mod 版本
            # 如果中间有 token，可能 mc_version 其实是 mod 版本（如 sodium-0.5.3）
            if second_vi == first_vi + 1:
                version_str = tokens[second_vi]
            else:
                # 第一个版本号其实是 mod 版本（如 sodium-0.5.3.jar）
                version_str = mc_version
                mc_version = ""
        else:
            # 只有一个版本号，它是 mod 版本（如 sodium-0.5.3.jar）
            version_str = mc_version
            mc_version = ""

    mod_name = "-".join(tokens[:mod_name_end]) if mod_name_end > 0 else name

    # 生成搜索变体
    variants = set()
    if mod_name:
        variants.add(mod_name)
        variants.add(mod_name.replace("_", " "))
        variants.add(mod_name.replace("_", "-"))
        variants.add(mod_name.replace(" ", "-"))
        variants.add(mod_name.replace(" ", ""))
        variants.add(mod_name.replace("-", ""))
        # 去掉尾部数字（如 ExtendedAE2 → ExtendedAE）
        stripped = re.sub(r"\d+$", "", mod_name)
        if stripped and stripped != mod_name:
            variants.add(stripped)
            variants.add(stripped.replace("_", "-"))
    variants.discard("")

    return {
        "name": mod_name or name,
        "version": version_str,
        "mc_version": mc_version,
        "loader": loader or "",
        "name_variants": sorted(variants),
    }


def extract_search_query(filename: str) -> str:
    """旧接口：保留以兼容。返回 name_variants 的第一个。"""
    return parse_filename(filename)["name_variants"][0] if parse_filename(filename)["name_variants"] else filename


# ---------------------------------------------------------------------------
# 名称 + 版本号匹配
# ---------------------------------------------------------------------------

def normalize_name(s: str) -> str:
    """归一化名称：小写、去空格/连字符/下划线。"""
    return re.sub(r"[\s\-_]+", "", (s or "").lower())


def version_matches(version_str: str, version_number: str) -> bool:
    """
    判断文件名解析出的版本号与 Modrinth version_number 是否匹配。
    严格匹配，避免误升级到最新版本。

    规则（任一满足即匹配）：
      - 去掉 loader/+.xxx 后缀后完全相等
      - version_number 去掉 loader/+.xxx 后缀后，按 - 切段，
        version_str 作为其中一段（或多段拼接）出现

    例如:
      version_str="1.4.15", version_number="1.20-1.4.15-forge" → True
        b_base="1.20-1.4.15", 按 - 切段 ["1.20", "1.4.15"], "1.4.15" in 列表 → True
      version_str="1.4.15", version_number="1.20-1.4.12-forge" → False
        b_base="1.20-1.4.12", 切段 ["1.20", "1.4.12"], "1.4.15" 不在 → False
      version_str="14.1.20", version_number="14.1.20+forge-1.20.1" → True
        b_base="14.1.20", 切段 ["14.1.20"], 完全相等 → True
      version_str="0.5.3", version_number="mc1.20.1-0.5.3" → True
        b_base="mc1.20.1-0.5.3", 切段 ["mc1.20.1", "0.5.3"], "0.5.3" in 列表 → True
    """
    if not version_str or not version_number:
        return False
    a = version_str.lower().strip()
    b = version_number.lower().strip()

    # 去掉 +xxx 后缀
    a_base = a.split("+")[0]
    b_base = b.split("+")[0]

    # 去掉末尾的 -loader / .loader
    for loader_kw in ("forge", "fabric", "quilt", "neoforge"):
        a_base = re.sub(rf"[-.]{loader_kw}$", "", a_base)
        b_base = re.sub(rf"[-.]{loader_kw}$", "", b_base)

    if a_base == b_base:
        return True

    # 把 b_base 按 - 切段（保留 . 不切，因为版本号内部用 .）
    # 检查 a_base 是否等于其中一段
    b_segments = b_base.split("-")
    if a_base in b_segments:
        return True

    return False


def find_best_match_in_candidates(
    filename: str,
    parsed: Dict[str, str],
    candidates: List[Dict[str, Any]],
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]], str]:
    """
    在候选 project 列表中查找最佳匹配。
    返回 (version, file, match_type) 或 (None, None, "")。

    重要：本函数只做精确匹配，不会自动取最新版本。匹配规则：

    match_type 取值:
      "filename_exact"   - 文件名完全匹配（同一 jar，可能 SHA1 不同）
      "name_and_version" - slug 含于文件名 + version_number 严格匹配
                           （找的是用户文件名里写明的那个版本，不会升级到最新）

    如果两种匹配都失败，返回 (None, None, "")，由调用方决定是否记入 missing。
    """
    target_filename = filename.lower()
    target_name = parsed["name"]
    target_version = parsed["version"]

    # 第一轮：文件名完全匹配
    for cand in candidates:
        slug = cand.get("slug") or cand.get("project_id")
        if not slug:
            continue
        try:
            versions = get_project_versions(slug)
        except Exception:
            continue
        for v in versions:
            for f in v.get("files", []):
                if (f.get("filename") or "").lower() == target_filename:
                    return v, f, "filename_exact"
        time.sleep(0.05)

    # 第二轮：slug 含于文件名 + version_number 严格匹配
    # 只有当文件名解析出了版本号时才尝试
    if not target_version:
        return None, None, ""

    filename_norm = normalize_name(filename)
    for cand in candidates:
        slug = cand.get("slug") or ""
        title = cand.get("title") or ""
        slug_norm = normalize_name(slug)
        title_norm = normalize_name(title)
        if not slug_norm or not filename_norm:
            continue
        name_in_filename = (slug_norm in filename_norm) or (title_norm in filename_norm)
        if not name_in_filename:
            continue
        try:
            versions = get_project_versions(slug)
        except Exception:
            continue
        for v in versions:
            vnum = v.get("version_number", "") or ""
            if version_matches(target_version, vnum):
                # 找到匹配的 version，优先取 loader 匹配的 file
                for f in v.get("files", []):
                    fname_lower = (f.get("filename") or "").lower()
                    if parsed["loader"] and parsed["loader"] in fname_lower:
                        return v, f, "name_and_version"
                # 没找到 loader 匹配，取该 version 的第一个 file
                for f in v.get("files", []):
                    return v, f, "name_and_version"
        time.sleep(0.05)

    return None, None, ""


def find_matching_version_by_filename(
    filename: str,
    game_version: str,
    candidates: List[Dict[str, Any]],
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """旧接口：保留以兼容。仅做文件名完全匹配。"""
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
        time.sleep(0.1)
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
    返回 (entry, missing_info)。

    策略:
      1. SHA1 反查 Modrinth version_file → 命中即用
      2. 文件名搜索回退:
         a) 文件名完全匹配 → 用 Modrinth URL+SHA1 (match_type=filename_exact,
            但本地 SHA1 不同，所以 reason 标记 on_modrinth_different_jar_same_filename)
         b) slug 含于文件名 + version_number 匹配 → 用 Modrinth URL+SHA1
            (match_type=name_and_version)
         c) slug 含于文件名（不要求版本号匹配） → 用 Modrinth URL+SHA1
            (match_type=name_only，更弱的匹配，可能误判)
         d) 找到 project 但无任何匹配 → missing (reason=project_exists_but_no_matching_file)
         e) 完全没找到 → missing (reason=not_found_on_modrinth)

    注意: 当走 fallback 路径(b/c)时，写入 JSON 的是 Modrinth 上的 SHA1，
    客户端会下载 Modrinth 版本（本地 jar 与 Modrinth 上的可能略有差异，但功能等价）。
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
        return None, {
            "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
            "reason": "sha1_not_in_version_files",
            "version_id": version.get("id"), "project_id": version.get("project_id"),
        }

    # 2. 文件名搜索回退
    if enable_fallback_search:
        parsed = parse_filename(jar.name)
        # 用多个变体搜索，合并候选
        all_candidates: Dict[str, Dict[str, Any]] = {}  # slug → cand
        for variant in parsed["name_variants"][:4]:  # 最多用前 4 个变体
            try:
                hits = search_modrinth(variant, limit=5)
            except Exception:
                hits = []
            for h in hits:
                slug = h.get("slug")
                if slug and slug not in all_candidates:
                    all_candidates[slug] = h
            time.sleep(0.05)
        candidates = list(all_candidates.values())

        if candidates:
            v_matched, f_matched, match_type = find_best_match_in_candidates(
                jar.name, parsed, candidates)
            if v_matched is not None and f_matched is not None:
                # 用 Modrinth 上的 URL 和 SHA1 写入 JSON
                modrinth_sha1 = (f_matched.get("hashes", {}) or {}).get("sha1", "")
                modrinth_sha512 = (f_matched.get("hashes", {}) or {}).get("sha512", "")
                download_url = f_matched.get("url") or ""
                # 用 v_matched.project_id 反查 candidates 找到真正匹配的 slug
                matched_project_id = v_matched.get("project_id")
                matched_slug = ""
                matched_title = ""
                for c in candidates:
                    if c.get("project_id") == matched_project_id:
                        matched_slug = c.get("slug", "")
                        matched_title = c.get("title", "")
                        break
                if download_url:
                    file_size = f_matched.get("size") or size
                    entry = {
                        "path": path_str,
                        "hashes": {
                            "sha1": modrinth_sha1,
                            "sha512": modrinth_sha512,
                        },
                        "downloads": [download_url],
                        "fileSize": int(file_size),
                        "env": {"client": client_env, "server": server_env},
                    }
                    # 同时记录 missing_info 用于日志展示（但 entry 优先）
                    miss_info = {
                        "filename": jar.name, "path": path_str,
                        "sha1_local": sha1_hash,
                        "sha1_modrinth": modrinth_sha1,
                        "size": size,
                        "reason": f"on_modrinth_matched_{match_type}",
                        "modrinth_project_slug": matched_slug,
                        "modrinth_project_title": matched_title,
                        "modrinth_project_id": matched_project_id,
                        "modrinth_version_id": v_matched.get("id"),
                        "modrinth_version_number": v_matched.get("version_number"),
                        "modrinth_file_url": download_url,
                        "modrinth_file_filename": f_matched.get("filename"),
                    }
                    return entry, miss_info

            # 没找到任何匹配，但项目存在
            top = candidates[0]
            return None, {
                "filename": jar.name, "path": path_str, "sha1": sha1_hash, "size": size,
                "reason": "project_exists_but_no_matching_file",
                "modrinth_project_slug": top.get("slug"),
                "modrinth_project_id": top.get("project_id"),
                "modrinth_project_title": top.get("title"),
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
        if reason.startswith("on_modrinth_matched_"):
            extra = f" (project={m.get('modrinth_project_slug')} ver={m.get('modrinth_version_number')})"
            reason_display = reason + extra
        elif reason == "on_modrinth_but_different_jar":
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
    if reason.startswith("on_modrinth_matched_filename_exact"):
        return "Modrinth 上有同名 jar（同 version，但本地 SHA1 不同） → 已用 Modrinth URL+SHA1 写入 JSON（客户端会下载 Modrinth 版本，不会升级到最新）"
    if reason.startswith("on_modrinth_matched_name_and_version"):
        return "Modrinth 上有此 mod，slug 含于文件名 + 版本号严格匹配 → 已用 Modrinth URL+SHA1 写入 JSON（找的是用户文件名里写明的版本，不会升级到最新）"
    return {
        "not_found_on_modrinth": "Modrinth 上完全没有此 mod（搜索 0 命中）",
        "project_exists_but_no_matching_file": "Modrinth 上有此 mod 项目，但没有 version 的版本号与文件名解析出的版本号匹配（也不会自动取最新版）",
        "on_modrinth_but_different_jar": "Modrinth 上有同名文件，但 SHA1 与本地 jar 不同（旧 reason，新版已被 on_modrinth_matched_filename_exact 替代）",
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
    print(f"[INFO] 并发线程数: {args.threads}")

    jars = collect_jars(mods_dir, args.include_disabled, recursive=not args.no_recursive)
    if not jars:
        print(f"[WARN] {mods_dir} 下没有 .jar 文件", file=sys.stderr)
    else:
        print(f"[INFO] 扫描到 {len(jars)} 个 .jar 文件，开始并行处理...")

    entries: List[Dict[str, Any]] = []
    missing: List[Dict[str, Any]] = []
    # 按文件名索引结果，保证输出顺序稳定
    results_by_name: Dict[str, Tuple[int, Optional[Dict[str, Any]], Optional[Dict[str, Any]]]] = {}

    def process_one(idx_and_jar: Tuple[int, Path]) -> Tuple[int, str, Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
        idx, jar = idx_and_jar
        try:
            entry, miss = build_file_entry(
                jar, mods_dir, args.client_env, args.server_env,
                args.mc_version, args.enable_fallback_search,
            )
        except Exception as e:
            return idx, jar.name, None, {
                "filename": jar.name,
                "path": str(jar.relative_to(mods_dir.parent)).replace("\\", "/"),
                "reason": f"exception: {e}",
            }
        return idx, jar.name, entry, miss

    start_time = time.time()
    with ThreadPoolExecutor(max_workers=max(1, args.threads)) as pool:
        futures = [pool.submit(process_one, (i, jar)) for i, jar in enumerate(jars)]
        completed = 0
        for fut in as_completed(futures):
            try:
                idx, name, entry, miss = fut.result()
            except Exception as e:
                print(f"[ERROR] future failed: {e}", file=sys.stderr)
                continue
            completed += 1
            results_by_name[name] = (idx, entry, miss)
            # 实时输出（顺序可能乱，但带进度）
            size = jars[idx].stat().st_size
            if entry is not None:
                if miss and miss.get("reason", "").startswith("on_modrinth_matched_"):
                    print(f"[{completed:>3}/{len(jars)}] {name} ({human_size(size)}) — FOUND (via {miss['reason']}, project={miss.get('modrinth_project_slug')}, modr_ver={miss.get('modrinth_version_number')})")
                else:
                    print(f"[{completed:>3}/{len(jars)}] {name} ({human_size(size)}) — FOUND  url={entry['downloads'][0][:80]}...")
            else:
                reason = miss.get("reason", "unknown") if miss else "unknown"
                print(f"[{completed:>3}/{len(jars)}] {name} ({human_size(size)}) — NOT FOUND ({reason})")

    # 按 jar 原始顺序整理结果
    for jar in jars:
        idx, entry, miss = results_by_name.get(jar.name, (0, None, None))
        if entry is not None:
            entries.append(entry)
        elif miss is not None:
            missing.append(miss)

    elapsed = time.time() - start_time
    print(f"[INFO] 并行处理耗时: {elapsed:.1f}s")

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
                if m.get("reason", "").startswith("on_modrinth_matched_"):
                    extra = f"modrinth_slug={m.get('modrinth_project_slug')} modrinth_ver={m.get('modrinth_version_number')} modrinth_url={m.get('modrinth_file_url')}"
                elif m.get("reason") == "on_modrinth_but_different_jar":
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

    # 6. 线程数
    threads_input = prompt("并发线程数 (回车=4): ", "4").strip() or "4"
    try:
        threads = max(1, int(threads_input))
    except ValueError:
        threads = 4
    print()

    # 7. env
    print("文件 env 字段:")
    print("  client/server 取值: required / optional / unsupported")
    client_env = prompt("env.client (回车=required): ", "required").strip() or "required"
    server_env = prompt("env.server (回车=optional): ", "optional").strip() or "optional"
    print()

    # 8. 确认
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
    print(f"  并发线程数:          {threads}")
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
        threads=threads,
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
        "--threads", "-t", type=int, default=4,
        help="并发线程数（默认: 4）",
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
