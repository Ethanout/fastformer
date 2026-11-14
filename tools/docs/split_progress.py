"""Split the architecture refactor progress log into a short index and per-topic archives.

Usage:
    python -I tools/docs/split_progress.py [progress.md] [--dry-run]

The main file keeps its title, intro and the living sections listed in KEEP.
Every other "## " section is moved (appended, in order) to
<progress-dir>/<topic>.md and replaced by one index line.
Re-running is safe: only sections still present in the main file are moved,
and a heading already present in an archive is not appended twice.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

DEFAULT = Path("docs/reviews/2026-11-13-architecture-refactor-progress.md")
ARCHIVE_DIR_NAME = "progress"

# Living sections that stay in the main file (matched by heading prefix).
KEEP = ("当前状态与剩余计划",)

INDEX_START = "<!-- progress-index:start -->"
INDEX_END = "<!-- progress-index:end -->"

# (file stem, title, keywords); first match wins, order matters.
TOPICS = [
    ("binding", "数据化交互绑定 / 长按画线", ("绑定", "长按", "操作树")),
    ("preview", "预览与渲染", ("预览", "绘制", "透明度", "动画", "渲染", "视觉", "HUD")),
    ("persistence", "缺陷修复、历史与恢复持久化", ("缺陷", "历史", "恢复", "持久化", "信封", "日志", "Alpha", "旧格式", "重放")),
    ("world-task", "世界写入与任务", ("任务", "写入", "租约", "上传", "账本", "准入", "解码", "停放")),
    ("geometry", "几何与数学", ("几何", "数学", "向量", "主轴", "分量", "四边形", "算法", "轴")),
    ("client-input", "客户端输入与模式", ("模式", "指针", "输入", "滚轮", "按键", "鼠标", "手势", "路径")),
    ("client-session", "客户端会话、选区与提交", ("客户端", "作用域", "仓库", "剪贴板", "回执", "提交", "删除", "选区", "变换", "编辑上下文", "实例迁移")),
    ("server", "服务端会话、意图与协议", ("服务", "意图", "命令", "设置", "会话", "玩家", "协议", "起形", "快速替换", "填充", "共享规则")),
]
FALLBACK = ("early", "早期记录与验证")

SECTION_RE = re.compile(r"^## (.+?)\s*$", re.M)


def topic_for(heading: str) -> tuple[str, str]:
    for stem, title, words in TOPICS:
        if any(w in heading for w in words):
            return stem, title
    return FALLBACK


def split_sections(text: str) -> tuple[str, list[tuple[str, str]]]:
    matches = list(SECTION_RE.finditer(text))
    if not matches:
        return text, []
    head = text[: matches[0].start()]
    sections = []
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        sections.append((m.group(1), text[m.start():end].rstrip() + "\n"))
    return head, sections




def main(argv: list[str]) -> int:
    dry = "--dry-run" in argv
    args = [a for a in argv if not a.startswith("--")]
    main_file = Path(args[0]) if args else DEFAULT
    text = main_file.read_text(encoding="utf-8")
    archive_dir = main_file.parent / ARCHIVE_DIR_NAME

    while INDEX_START in text:  # drop every previously generated index block, even malformed ones
        a = text.index(INDEX_START)
        b = text.find(INDEX_END, a)
        text = text[:a] + (text[b + len(INDEX_END):] if b >= 0 else "")
    head, sections = split_sections(text)
    head = head.rstrip() + "\n"
    kept, moved = [], {}
    for heading, body in sections:
        if heading.startswith(KEEP) or heading == "阶段记录索引":
            if heading != "阶段记录索引":
                kept.append(body)
            continue
        stem, title = topic_for(heading)
        moved.setdefault((stem, title), []).append((heading, body))

    # Append to archives, skipping headings already archived.
    existing_index: dict[tuple[str, str], list[tuple[str, str]]] = {}
    for stem, title, _ in TOPICS + [(*FALLBACK, ())]:
        path = archive_dir / f"{stem}.md"
        old = path.read_text(encoding="utf-8") if path.exists() else ""
        _, old_sections = split_sections(old)
        known = {h for h, _ in old_sections}
        new = [(h, b) for h, b in moved.get((stem, title), []) if h not in known]
        if new and not dry:
            archive_dir.mkdir(parents=True, exist_ok=True)
            if not old:
                old = (f"# 架构重构进度归档：{title}\n\n"
                       f"返回 [进度索引](../{main_file.name})。按时间顺序追加，不在此处维护当前计划。\n\n")
            path.write_text(old.rstrip() + "\n\n" + "\n".join(b for _, b in new), encoding="utf-8")
        all_sections = old_sections + new
        if all_sections:
            existing_index[(stem, title)] = all_sections

    lines = [INDEX_START, "", "## 阶段记录索引", "",
             f"阶段细节已归档到 `{ARCHIVE_DIR_NAME}/`，按需打开对应主题，不要整体读取。"
             f"新阶段记录可以直接追加到本文件末尾，再运行 `python -I tools/docs/split_progress.py` 归档。", ""]
    for (stem, title), items in existing_index.items():
        lines.append(f"- **[{title}]({ARCHIVE_DIR_NAME}/{stem}.md)**（{len(items)} 节）")
        for h, b in items:
            lines.append(f"  - {h}")
    lines += ["", INDEX_END, ""]

    out = head + "\n" + "\n".join(k.rstrip() + "\n" for k in kept) + "\n" + "\n".join(lines)
    moved_count = sum(len(v) for v in moved.values())
    if dry:
        print(f"[dry-run] would move {moved_count} sections; main file would be {len(out.splitlines())} lines")
        for (stem, _), items in moved.items():
            print(f"  {stem}: {len(items)}")
        return 0
    main_file.write_text(out, encoding="utf-8")
    print(f"moved {moved_count} sections into {archive_dir}; main file now {len(out.splitlines())} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
