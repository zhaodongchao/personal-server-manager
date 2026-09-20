#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen-iconify-offline.py  ——  ServerPanel 前端「图标离线化」生成器
作者: zhaodc   创建时间: 2026-09-21（v2: 2026-09-21 全量本地集合）

背景
----
`@iconify/vue` 的 `<Icon>` 组件默认在**运行时**从公网 CDN 拉图标数据，
资源列表为 [api.iconify.design, api.simplesvg.com, api.unisvg.com]（后两个是随机顺序的兜底）。
生产环境（内网 / 中国大陆 / 企业代理）这三个域名通常不可达，导致：
  1. 每次首屏/菜单渲染都会挂起若干 HTTP 请求，直到 TCP 超时（数十秒）；
  2. 图标始终空白；
  3. 控制台大量 `net::ERR_CONNECTION_TIMED_OUT`。

本脚本把图标数据从本地 `@iconify/json`（已是 devDependency）抽取出来，
生成纯静态产物，彻底消除运行时外呼：

  1) apps/web-antd/public/iconify-preload.js
     - window.IconifyPreload   : 内联「源码实际用到」的图标数据（约 10KB），
                                `@iconify/vue` 初始化时自动 addCollection（零请求、立即可渲染）
     - window.IconifyProviders : 把图标 API 指向站内 /iconify/，
                                未内联的图标走本地静态文件（毫秒级），
                                且**不会**再回退到 api.iconify.design / *.unisvg.com

  2) apps/web-antd/public/iconify/collections.json
     - 各图标集的「图标名清单」，供 @vben 的 IconPicker（packages/effects/common-ui）
       在离线环境下展示可选图标，替代原先的 https://api.iconify.design/collection

  3) apps/web-antd/public/iconify/<prefix>.json
     - **全量**图标集（仅 FULL_SET_PREFIXES）或项目子集（其余前缀），
       结构与 iconify API 返回一致；作为 /iconify/ 本地兜底资源。
     - v2 起：图标选择器可选的前缀（lucide/ant-design/ep/fluent-mdl2）导出**全量**，
       这样「数据库菜单图标」「运行时动态图标」「图标选择器预览」三类运行时才知道名字的
       图标也能在本地命中，不需要任何公网请求。
     - 全量集合内**不携带 aliases 表**：别名在生成期即解析成实体图标
       （否则 mdi 约 3000 条、lucide 约 800 条别名会让体积翻倍）。

图标来源 = 源码扫描（apps/web-antd/src + packages/**，匹配 `prefix:name` 字面量）
         ∪ 显式补充（EXTRA_ICONS，数据库菜单/后端下发等源码里搜不到的）

用法（在 frontend/ 目录下执行）
    python3 scripts/gen-iconify-offline.py
"""

from __future__ import annotations

import glob
import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
FRONTEND_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUBLIC_DIR = os.path.join(FRONTEND_DIR, "apps", "web-antd", "public")
ICONIFY_OUT_DIR = os.path.join(PUBLIC_DIR, "iconify")
PRELOAD_FILE = os.path.join(PUBLIC_DIR, "iconify-preload.js")
COLLECTIONS_FILE = os.path.join(ICONIFY_OUT_DIR, "collections.json")

# 源码扫描范围（相对 frontend/）
SCAN_DIRS = [
    "apps/web-antd/src",
    "packages",
]

# 需要生成的图标集前缀（超出这些前缀的图标不生成，运行时快速失败即可）
PREFIXES = ["lucide", "ant-design", "carbon", "ep", "fluent-mdl2", "mdi"]

# 额外导出「图标名清单」（供 IconPicker 下拉展示）的前缀。
# 只挑体积可控、业务真正会用的；mdi 有 14001 个图标（约 165KB 名字），故不导出。
PICKER_PREFIXES = ["lucide", "ant-design", "ep", "fluent-mdl2"]

# 导出**全量**图标数据的（/iconify/<prefix>.json）。
# 取舍依据：
#   - 这 4 个前缀是图标选择器可选范围 ⇒ 数据库菜单图标、用户自选图标只可能来自它们；
#     全量导出后，任何运行时才出现的图标名都能在本地命中（约 2.2MB 静态文件，按需加载）。
#   - mdi(3.4MB) / carbon(1.2MB) 仅出现在源码字面量里，已由内联 preload 覆盖，故只导出子集。
FULL_SET_PREFIXES = ["lucide", "ant-design", "ep", "fluent-mdl2"]

# 源码里搜不到、但运行时会用到的图标（数据库菜单表 t_sys_menu.icon 等）。
# 维护方式：在「系统管理 → 菜单管理」里新增菜单后，把新图标补到这里再重跑本脚本。
EXTRA_ICONS = [
    # 侧边栏菜单图标（来自数据库菜单配置）
    "ant-design:appstore-filled",
    "ant-design:bank-filled",
    "lucide:activity",
    "lucide:folder",
    "lucide:gauge",
    "lucide:layers",
    "lucide:layout-dashboard",
    "lucide:settings",
    "lucide:wrench",
]

ICON_RE = re.compile(
    r"\b(" + "|".join(re.escape(p) for p in PREFIXES) + r"):([a-zA-Z0-9][a-zA-Z0-9-]*)"
)

SKIP_DIR_PARTS = ("node_modules", "/dist/", "/.git/", "/tests/", "__tests__")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------
def find_iconify_json_dir() -> str:
    """定位 @iconify/json 的 json/ 目录（兼容 pnpm 的 .pnpm 布局）。"""
    candidates = [
        os.path.join(FRONTEND_DIR, "node_modules", "@iconify", "json", "json"),
    ]
    candidates += glob.glob(
        os.path.join(
            FRONTEND_DIR,
            "node_modules",
            ".pnpm",
            "@iconify+json@*",
            "node_modules",
            "@iconify",
            "json",
            "json",
        )
    )
    for c in candidates:
        if os.path.isdir(c):
            return c
    raise SystemExit(
        "找不到 @iconify/json 的 json/ 目录，请先在 frontend/ 下执行 pnpm install"
    )


def scan_source_icons() -> dict:
    """扫描源码，返回 {prefix: set(names)}。"""
    found: dict = {}
    for rel in SCAN_DIRS:
        root = os.path.join(FRONTEND_DIR, rel)
        if not os.path.isdir(root):
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            if any(p in dirpath for p in SKIP_DIR_PARTS):
                continue
            dirnames[:] = [d for d in dirnames if d not in ("node_modules", "dist")]
            for fn in filenames:
                if not fn.endswith((".ts", ".tsx", ".vue", ".js", ".mjs", ".json")):
                    continue
                p = os.path.join(dirpath, fn)
                if any(part in p for part in SKIP_DIR_PARTS):
                    continue
                try:
                    with open(p, "r", encoding="utf-8", errors="ignore") as f:
                        text = f.read()
                except OSError:
                    continue
                for prefix, name in ICON_RE.findall(text):
                    found.setdefault(prefix, set()).add(name)
    return found


def parse_icon_name(spec: str):
    prefix, _, name = spec.partition(":")
    if not prefix or not name:
        raise ValueError("非法图标标识: %r" % spec)
    return prefix, name


def resolve_icon(collection: dict, name: str):
    """
    从 collection 中取出图标定义。
    图标可能定义在 `icons` 或 `aliases`（别名需回溯到 parent）。返回 (body_dict, resolved_name)。
    """
    icons = collection.get("icons") or {}
    aliases = collection.get("aliases") or {}

    if name in icons:
        return icons[name], name

    seen = set()
    cur = name
    while cur in aliases and cur not in seen:
        seen.add(cur)
        parent = (aliases.get(cur) or {}).get("parent")
        if not parent:
            return None, None
        if parent in icons:
            return icons[parent], parent
        cur = parent
    return None, None


def resolve_all_icons(collection: dict) -> dict:
    """
    把集合的 icons + aliases 展开成「全部实体图标」表（含多级别名回搠）。
    产物里因此不再需要 aliases 表。
    """
    icons = {k: dict(v) for k, v in (collection.get("icons") or {}).items()}
    pending = {
        k: dict(v)
        for k, v in (collection.get("aliases") or {}).items()
        if k not in icons
    }
    # 多轮遍历：别名可能指向另一个别名
    for _ in range(10):
        if not pending:
            break
        progressed = False
        for name in list(pending.keys()):
            props = pending[name]
            parent = props.get("parent")
            if parent and parent in icons:
                merged = dict(icons[parent])
                merged.update({k: v for k, v in props.items() if k != "parent"})
                icons[name] = merged
                del pending[name]
                progressed = True
        if not progressed:
            break
    if pending:
        sys.stderr.write(
            "[WARN] %d 个别名无法回搠到实体图标，已忽略\n" % len(pending)
        )
    return icons


# ---------------------------------------------------------------------------
# 生成
# ---------------------------------------------------------------------------
def main() -> int:
    json_dir = find_iconify_json_dir()
    print("[iconify-json] %s" % json_dir)

    wanted = scan_source_icons()
    for spec in EXTRA_ICONS:
        prefix, name = parse_icon_name(spec)
        wanted.setdefault(prefix, set()).add(name)

    os.makedirs(ICONIFY_OUT_DIR, exist_ok=True)

    preload = []             # window.IconifyPreload 的集合数组（仅内联「用到的」图标）
    collections_names = {}   # collections.json 内容
    total_preload = 0
    missing = []

    print("")
    print("  %-14s %-22s %s" % ("prefix", "内联(预加载)", "本地 /iconify/<prefix>.json"))
    for prefix in PREFIXES:
        names = sorted(wanted.get(prefix) or [])
        if not names:
            continue

        src = os.path.join(json_dir, prefix + ".json")
        if not os.path.isfile(src):
            missing.append("%s(集合文件不存在)" % prefix)
            continue
        with open(src, "r", encoding="utf-8") as f:
            full = json.load(f)

        subset_icons = {}
        for nm in names:
            body, resolved = resolve_icon(full, nm)
            if body is None:
                missing.append("%s:%s" % (prefix, nm))
                continue
            subset_icons[nm] = body

        if not subset_icons:
            continue

        # 注意：**不携带 aliases 表**。别名已在本脚本内解析成实体图标（见 resolve_icon），
        # 而完整 aliases 表体积巨大（mdi 约 3000 条、lucide 约 800 条），
        # 带进产物会让预加载文件从约 20KB 膨胀到约 370KB。
        coll = {
            "prefix": prefix,
            "icons": subset_icons,
            "width": full.get("width", 24),
            "height": full.get("height", 24),
        }
        preload.append(coll)

        # ---- /iconify/<prefix>.json：全量 or 子集 ----
        if prefix in FULL_SET_PREFIXES:
            shipped = {
                "prefix": prefix,
                "icons": resolve_all_icons(full),
                "width": full.get("width", 24),
                "height": full.get("height", 24),
            }
            shipped_path = os.path.join(ICONIFY_OUT_DIR, prefix + ".json")
            with open(shipped_path, "w", encoding="utf-8") as f:
                json.dump(shipped, f, ensure_ascii=True, separators=(",", ":"))
            shipped_desc = "全量 %5d 个 (%.0f KB)" % (
                len(shipped["icons"]),
                os.path.getsize(shipped_path) / 1024.0,
            )
        else:
            with open(os.path.join(ICONIFY_OUT_DIR, prefix + ".json"), "w", encoding="utf-8") as f:
                json.dump(coll, f, ensure_ascii=True, separators=(",", ":"))
            shipped_desc = "子集 %5d 个" % len(subset_icons)

        # 供 IconPicker 使用的「图标名清单」。
        # 说明：清单体积 = 图标集总图标数，mdi 一个集就有 14001 个名字（约 165KB），
        # 而面板的图标选择器默认只用 ant-design/lucide，因此这里只导出体积可控、
        # 业务真正会用到的图标集；未导出的前缀在选择器里表现为「空列表」（毫秒级返回，不再外呼）。
        all_names = sorted(
            set((full.get("icons") or {}).keys()) | set((full.get("aliases") or {}).keys())
        )
        if prefix in PICKER_PREFIXES:
            info = full.get("info") or {}
            title = info.get("name") or prefix
            collections_names[prefix] = {
                "prefix": prefix,
                "total": len(all_names),
                "title": title,
                "uncategorized": all_names,
            }

        total_preload += len(subset_icons)
        print("  %-14s %-22s %s"
              % (prefix, "内联 %3d 个" % len(subset_icons), shipped_desc))

    if not preload:
        print("ERROR: 没有生成任何图标集合", file=sys.stderr)
        return 1

    # ---- iconify-preload.js ----
    preload_js = json.dumps(preload, ensure_ascii=True, separators=(",", ":"))
    body = (
        "/* eslint-disable */\n"
        "/* 由 frontend/scripts/gen-iconify-offline.py 自动生成，请勿手改。\n"
        " * 作用：图标数据内联 + 把图标 API 指向站内 /iconify/，避免运行时访问公网 CDN。\n"
        " * 关键：IconifyProviders[''] 必须是**唯一**资源（站点根相对路径 /iconify/），\n"
        " *       这样 @iconify/vue 默认的 [api.iconify.design, api.simplesvg.com, api.unisvg.com]\n"
        " *       会被整体替换，任何图标都只可能打到本机。\n"
        " */\n"
        "(function () {\n"
        "  window.IconifyPreload = %s;\n"
        "  window.IconifyProviders = {\n"
        "    '': {\n"
        "      resources: ['/iconify/'],\n"
        "      path: '/iconify/',\n"
        "      maxURL: 500,\n"
        "      rotate: 100,\n"
        "      timeout: 3000,\n"
        "      random: false,\n"
        "      index: 0,\n"
        "      dataAfterTimeout: false,\n"
        "    },\n"
        "  };\n"
        "})();\n"
    ) % preload_js
    with open(PRELOAD_FILE, "w", encoding="utf-8") as f:
        f.write(body)

    with open(COLLECTIONS_FILE, "w", encoding="utf-8") as f:
        json.dump(collections_names, f, ensure_ascii=True, separators=(",", ":"))

    iconify_total = sum(
        os.path.getsize(os.path.join(ICONIFY_OUT_DIR, n))
        for n in os.listdir(ICONIFY_OUT_DIR)
    )

    print("")
    print("[OK] %s  (%d 个集合 / %d 个图标, %.1f KB)"
          % (os.path.relpath(PRELOAD_FILE, FRONTEND_DIR), len(preload), total_preload,
             os.path.getsize(PRELOAD_FILE) / 1024.0))
    print("[OK] %s  (%.1f KB)"
          % (os.path.relpath(COLLECTIONS_FILE, FRONTEND_DIR),
             os.path.getsize(COLLECTIONS_FILE) / 1024.0))
    print("[OK] %s/  合计 %.1f KB（按需加载，仅未内联图标才会请求）"
          % (os.path.relpath(ICONIFY_OUT_DIR, FRONTEND_DIR), iconify_total / 1024.0))
    if missing:
        print("[WARN] 未找到的图标（运行时将快速失败、显示为空白）: %s" % ", ".join(missing))
    return 0


if __name__ == "__main__":
    sys.exit(main())
