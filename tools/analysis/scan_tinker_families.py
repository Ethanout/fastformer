"""Find candidate block families in local Minecraft assets and source archives.

This produces evidence for review, not automatic block conversion rules.
Uses only the Python standard library. Run from any working directory.
"""

import argparse
from collections import defaultdict
import csv
from functools import cache
import json
from pathlib import Path
import re
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = "net/minecraft/"


def group_affixes(ids, side):
    groups = []
    for length in range(3, 17):
        buckets = defaultdict(list)
        for block_id in ids:
            if len(block_id) >= length:
                token = block_id[:length] if side == "prefix" else block_id[-length:]
                buckets[token].append(block_id)
        for token, members in sorted(buckets.items()):
            if len(members) > 1:
                groups.append({"length": length, "token": token, "members": members})
    return groups


def unique_affixes(groups):
    by_members = {}
    for group in groups:
        # Keep the longest matching token when several lengths have the same members.
        by_members[tuple(group["members"])] = group
    return sorted(by_members.values(), key=lambda group: (-len(group["members"]), group["token"]))


def collect_models(value):
    if isinstance(value, dict):
        if "model" in value:
            yield value["model"]
        for child in value.values():
            yield from collect_models(child)
    elif isinstance(value, list):
        for child in value:
            yield from collect_models(child)


def model_slots(blockstate):
    slots = defaultdict(set)
    for variant in blockstate.get("variants", {}):
        for entry in variant.split(","):
            if "=" in entry:
                name, value = entry.split("=", 1)
                slots[name].update(value.split("|"))

    def conditions(value):
        if isinstance(value, list):
            for child in value:
                conditions(child)
        elif isinstance(value, dict):
            for key, child in value.items():
                if key in ("OR", "AND"):
                    conditions(child)
                elif isinstance(child, str):
                    slots[key].update(child.split("|"))

    for part in blockstate.get("multipart", []):
        conditions(part.get("when", {}))
    return {name: sorted(values) for name, values in sorted(slots.items())}


def read_assets(archive):
    states = {}
    models = {}
    for name in archive.namelist():
        if name.startswith("assets/minecraft/blockstates/") and name.endswith(".json"):
            states[Path(name).stem] = json.loads(archive.read(name))
        elif name.startswith("assets/minecraft/models/") and name.endswith(".json"):
            models[name.removeprefix("assets/minecraft/models/").removesuffix(".json")] = json.loads(archive.read(name))

    @cache
    def inherited_textures(model):
        model = model.removeprefix("minecraft:")
        data = models.get(model, {})
        textures = dict(inherited_textures(data["parent"])) if "parent" in data else {}
        textures.update(data.get("textures", {}))
        return textures

    def textures_for(model):
        bindings = inherited_textures(model)
        for value in bindings.values():
            visited = set()
            while value.startswith("#") and value not in visited:
                visited.add(value)
                value = bindings.get(value[1:], "")
            if value and not value.startswith("#"):
                yield value.removeprefix("minecraft:")

    details = {}
    for block_id, state in sorted(states.items()):
        used_models = sorted(set(collect_models(state)))
        textures = sorted({texture for model in used_models for texture in textures_for(model)})
        details[block_id] = {"model_slots": model_slots(state), "models": used_models, "textures": textures}
    return details


def source_evidence(archive):
    blocks = archive.read(SOURCE_ROOT + "world/level/block/Blocks.java").decode("utf-8")
    registrations = dict(re.findall(r'public static final Block\s+(\w+)\s*=\s*register\(\s*"([^"]+)"', blocks))
    references = archive.read(SOURCE_ROOT + "references/Blocks.java").decode("utf-8")
    keys = dict(re.findall(r'ResourceKey<Block>\s+(\w+)\s*=\s*createKey\("([^"]+)"\)', references))
    for field, reference in re.findall(r'public static final Block\s+(\w+)\s*=\s*register\(\s*net.minecraft.references.Blocks\.(\w+)', blocks):
        registrations[field] = keys[reference]
    relations = []
    mappings = {
        "strip": "world/item/AxeItem.java",
        "wax": "world/item/HoneycombItem.java",
        "weather": "world/level/block/WeatheringCopper.java",
    }
    for kind, path in mappings.items():
        source = archive.read(SOURCE_ROOT + path).decode("utf-8")
        for match in re.finditer(r'\.put\(Blocks\.(\w+),\s*Blocks\.(\w+)\)', source):
            first, second = match.groups()
            if first in registrations and second in registrations:
                relations.append({"kind": kind, "from": registrations[first], "to": registrations[second],
                                  "source": path, "line": source.count("\n", 0, match.start()) + 1})
    return registrations, relations


def write_csv(path, rows, columns):
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=columns)
        writer.writeheader()
        for row in rows:
            writer.writerow({name: " ".join(row[name]) if isinstance(row[name], list) else row[name] for name in columns})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, default=ROOT / "build/jars/extra/client/1.21.1/client-extra.jar")
    parser.add_argument("--sources", type=Path, default=ROOT / "build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/patch/outputs.jar")
    parser.add_argument("--output", type=Path, default=ROOT / ".temp/tinker-scan")
    args = parser.parse_args()
    with ZipFile(args.assets) as assets, ZipFile(args.sources) as sources:
        details = read_assets(assets)
        registrations, relations = source_evidence(sources)
    ids = sorted(set(registrations.values()))
    excluded_assets = sorted(set(details) - set(ids))
    details = {block_id: detail for block_id, detail in details.items() if block_id in ids}
    prefixes = group_affixes(ids, "prefix")
    suffixes = group_affixes(ids, "suffix")
    slots = defaultdict(list)
    textures = defaultdict(list)
    signatures = defaultdict(list)
    for block_id, detail in details.items():
        for slot in detail["model_slots"]:
            slots[slot].append(block_id)
        signature = json.dumps(detail["model_slots"], sort_keys=True)
        if detail["model_slots"]:
            signatures[signature].append(block_id)
        for texture in detail["textures"]:
            textures[texture].append(block_id)
    shared_textures = {key: value for key, value in sorted(textures.items()) if len(value) > 1}
    shared_slots = {key: value for key, value in sorted(slots.items()) if len(value) > 1}
    shared_signatures = {key: value for key, value in sorted(signatures.items()) if len(value) > 1}
    report = {"inputs": {"assets": str(args.assets), "sources": str(args.sources)},
              "block_count": len(ids), "asset_block_count": len(details),
              "source_registered_count": len(registrations),
              "excluded_nonblock_assets": excluded_assets,
              "prefixes": prefixes, "suffixes": suffixes,
              "unique_prefixes": unique_affixes(prefixes), "unique_suffixes": unique_affixes(suffixes),
              "shared_model_slots": shared_slots, "shared_slot_signatures": shared_signatures,
              "shared_textures": shared_textures, "vanilla_relations": relations, "blocks": details}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "families.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for side, groups in (("prefix", prefixes), ("suffix", suffixes)):
        write_csv(args.output / f"{side}-3-16.csv", groups, ["length", "token", "members"])
    write_csv(args.output / "vanilla-relations.csv", relations, ["kind", "from", "to", "source", "line"])
    write_csv(args.output / "shared-slots.csv", [{"slot": key, "members": value} for key, value in shared_slots.items()], ["slot", "members"])
    write_csv(args.output / "shared-textures.csv", [{"texture": key, "members": value} for key, value in shared_textures.items()], ["texture", "members"])
    lines = ["# 扳手候选家族扫描", "", f"方块 ID：{len(ids)}；资源定义：{len(details)}；源码注册：{len(registrations)}。", "",
             "排除非方块资源：" + "、".join(excluded_assets) + "。", "",
             "前缀、后缀分别按 3～16 字符扫描。ID 不含命名空间，重复成员集在摘要中保留最长匹配。", "",
             "结果仅供审查：名称相似和共享材质不代表可以转换。slot 来自方块状态模型条件，未列出不影响模型的运行时属性。", "",
             "| 长度 | 前缀组数 | 后缀组数 |", "| --- | --- | --- |"]
    for length in range(3, 17):
        lines.append(f"| {length} | {sum(g['length'] == length for g in prefixes)} | {sum(g['length'] == length for g in suffixes)} |")
    for title, groups in (("前缀", prefixes), ("后缀", suffixes)):
        lines.extend(["", f"## {title}去重摘要", ""])
        for group in unique_affixes(groups):
            lines.append(f"- `{group['token']}`（{group['length']}）：" + "、".join(f"`{member}`" for member in group["members"]))
    lines.extend(["", "## 共享模型状态属性", ""])
    for name, members in shared_slots.items():
        lines.append(f"- `{name}`（{len(members)}）：" + "、".join(members))
    lines.extend(["", "## 原版转换映射", ""])
    for relation in relations:
        lines.append(f"- {relation['kind']}: `{relation['from']}` → `{relation['to']}`（{relation['source']}:{relation['line']}）")
    (args.output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"blocks": len(ids), "prefix_groups": len(prefixes), "suffix_groups": len(suffixes),
                      "unique_prefix_groups": len(unique_affixes(prefixes)), "unique_suffix_groups": len(unique_affixes(suffixes)),
                      "shared_model_slots": len(shared_slots), "shared_textures": len(shared_textures),
                      "vanilla_relations": len(relations), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
