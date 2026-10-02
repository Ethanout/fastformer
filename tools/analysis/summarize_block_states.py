"""Summarize the runtime registry export. Does not create wrench rules."""

import argparse
from collections import defaultdict
import json
from math import prod
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def collect(report):
    blocks = report["blocks"]
    groups = defaultdict(list)
    for block_id, block in blocks.items():
        properties = block["properties"]
        expected = prod(len(values) for values in properties.values())
        if expected != block["state_count"]:
            raise ValueError(f"{block_id}: expected {expected} states, got {block['state_count']}")
        for name, values in properties.items():
            groups[(name, tuple(values))].append(block_id)
    return groups


def copper_families(report):
    next_blocks = report["weathering"]
    waxing = report["waxing"]
    roots = sorted(set(next_blocks) - set(next_blocks.values()))
    result = []
    for root in roots:
        chain = [root]
        while chain[-1] in next_blocks:
            target = next_blocks[chain[-1]]
            if target in chain:
                raise ValueError(f"Oxidation mapping contains a cycle: {chain}")
            chain.append(target)
        waxed = [waxing[block_id] for block_id in chain]
        schemas = [report["blocks"][block_id]["properties"] for block_id in chain + waxed]
        result.append({"stages": chain, "waxed_stages": waxed, "same_properties": all(schema == schemas[0] for schema in schemas)})
    return result


def write_reports(report, groups, families, models, output):
    stateful = {block_id: block for block_id, block in report["blocks"].items() if block["properties"]}
    hidden = {}
    for block_id, block in stateful.items():
        model = models.get(block_id.removeprefix("minecraft:"))
        if model is not None:
            missing = sorted(set(block["properties"]) - set(model["model_slots"]))
            if missing:
                hidden[block_id] = missing
    summary = {
        "minecraft_version": report["minecraft_version"],
        "blocks": len(report["blocks"]),
        "blocks_with_properties": len(stateful),
        "property_names": sorted({name for name, _ in groups}),
        "property_domains": len(groups),
        "total_states": sum(block["state_count"] for block in report["blocks"].values()),
        "properties_absent_from_models": hidden,
        "copper_families": families,
        "pale_moss_carpet_present": "minecraft:pale_moss_carpet" in report["blocks"],
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [f"# Minecraft {report['minecraft_version']} 方块状态", ""]
    for index, (block_id, block) in enumerate(stateful.items(), 1):
        lines.extend([f"## {index}. {block_id}", "", f"状态数：{block['state_count']}。", ""])
        for name, values in block["properties"].items():
            lines.append(f"- `{name}`：{' / '.join(values)}。默认：{block['default'][name]}。")
        lines.append("")
    (output / "states.md").write_text("\n".join(lines), encoding="utf-8")
    lines = ["# 相同属性和合法取值", ""]
    for (name, values), members in sorted(groups.items()):
        lines.extend([f"## {name}：{' / '.join(values)}", "", f"方块数：{len(members)}。", ""])
        lines.extend(f"- `{block_id}`" for block_id in members)
        lines.append("")
    (output / "properties.md").write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items()
                      if key not in {"properties_absent_from_models", "copper_families", "property_names"}}, ensure_ascii=False))
    print(f"Property names: {len(summary['property_names'])}; blocks with model omissions: {len(hidden)}; copper families: {len(families)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=ROOT / ".temp/tinker-state-scan/registry.json")
    parser.add_argument("--models", type=Path, default=ROOT / ".temp/tinker-scan/families.json")
    parser.add_argument("--output", type=Path, default=ROOT / ".temp/tinker-state-scan")
    args = parser.parse_args()
    report = json.loads(args.registry.read_text(encoding="utf-8"))
    models = json.loads(args.models.read_text(encoding="utf-8"))["blocks"] if args.models.exists() else {}
    write_reports(report, collect(report), copper_families(report), models, args.output)


if __name__ == "__main__":
    main()
