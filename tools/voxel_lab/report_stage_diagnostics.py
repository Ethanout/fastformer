from __future__ import annotations

import collections
import itertools
import time

from ownership_carve import secondary_volume
from planar_faces import cross, dot
from stage_diagnostics import diagnose_three_stages


def report() -> None:
    candidates = [
        vector
        for vector in itertools.product(range(-1, 2), repeat=3)
        if vector != (0, 0, 0)
    ]
    statistics: collections.Counter[str] = collections.Counter()
    witnesses: dict[str, tuple[object, ...]] = {}
    started = time.time()
    print(f"starting vectors={len(candidates)}", flush=True)
    for edge_a in candidates:
        for edge_b in candidates:
            for edge_c in candidates:
                determinant = dot(
                    cross(
                        tuple(float(value) for value in edge_a),
                        tuple(float(value) for value in edge_b),
                    ),
                    tuple(float(value) for value in edge_c),
                )
                if abs(determinant) < 1.0e-9:
                    continue
                statistics["count"] += 1
                if statistics["count"] % 2_000 == 0:
                    print(f"checked={statistics['count']}", flush=True)
                result = diagnose_three_stages(
                    secondary_volume((0, 0, 0), edge_a, edge_b, edge_c)
                )
                for stage_name in ("pre_fill", "post_fill", "post_expose"):
                    stage = getattr(result, stage_name)
                    if stage.enclosed_air:
                        statistics[f"{stage_name}_enclosed_cases"] += 1
                        statistics[f"{stage_name}_enclosed_voxels"] += len(stage.enclosed_air)
                        witnesses.setdefault(
                            f"{stage_name}_enclosed",
                            (edge_a, edge_b, edge_c, len(stage.enclosed_air)),
                        )
                    if stage.open_halfspace_voids:
                        statistics[f"{stage_name}_open_cases"] += 1
                        statistics[f"{stage_name}_open_voxels"] += len(stage.open_halfspace_voids)
                        witnesses.setdefault(
                            f"{stage_name}_open",
                            (
                                edge_a,
                                edge_b,
                                edge_c,
                                len(stage.open_halfspace_voids),
                                stage.maximum_penetration_depth,
                            ),
                        )
                    statistics[f"{stage_name}_max_depth"] = max(
                        statistics[f"{stage_name}_max_depth"],
                        stage.maximum_penetration_depth,
                    )
                    statistics[f"{stage_name}_through"] += stage.through_component_count
                if result.expose_result.deleted_voxels:
                    statistics["carve_cases"] += 1
                    statistics["carve_deleted"] += result.expose_result.deleted_voxels
                    statistics["max_carve_depth"] = max(
                        statistics["max_carve_depth"],
                        result.expose_result.maximum_carve_depth,
                    )
                    witnesses.setdefault(
                        "carve",
                        (
                            edge_a,
                            edge_b,
                            edge_c,
                            result.expose_result.deleted_voxels,
                            result.expose_result.maximum_carve_depth,
                        ),
                    )
                if result.expose_manufactured_opening:
                    statistics["manufactured_cases"] += 1
                    statistics["manufactured_voxels"] += len(result.expose_new_open_voids)
                    witnesses.setdefault(
                        "manufactured",
                        (edge_a, edge_b, edge_c, len(result.expose_new_open_voids)),
                    )
                if result.expose_result.missing_outline:
                    statistics["missing_outline"] += 1
    print(f"vectors={len(candidates)} elapsed={time.time() - started:.3f}")
    print(dict(statistics))
    print(witnesses)


if __name__ == "__main__":
    report()
