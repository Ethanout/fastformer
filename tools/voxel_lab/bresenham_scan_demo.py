from pathlib import Path
import matplotlib.pyplot as plt
import numpy as np

# 只改这里
SPAN = (12, 5)          # Pi 的边
SWEEP = (5, 10)         # P0 的步进边
BRIDGE_AXIS = 0         # xy 同步推进时，检查哪个轴的特殊点
KEEP_END_SPECIAL = False
Z_PLANE = (3, -2, 7)    # z = round((3x-2y)/7)
EXTRUDE = (3, 4, 6)     # 体的 Bresenham 挤出


def bres(a, b):
    """任意维整数 Bresenham。"""
    p, d = list(a), [abs(y - x) for x, y in zip(a, b)]
    s, n = [1 if y >= x else -1 for x, y in zip(a, b)], max(d)
    if n == 0:
        return [tuple(p)]
    err, out = [n // 2] * len(p), []
    for _ in range(n + 1):
        out.append(tuple(p))
        for i in range(len(p)):
            err[i] -= d[i]
            if err[i] < 0:
                p[i] += s[i]
                err[i] += n
    return out


def add(a, b):
    return tuple(x + y for x, y in zip(a, b))


def scan_face(span, sweep):
    pi, walk = bres((0, 0), span), bres((0, 0), sweep)
    logical, bridges, pi_set = set(), set(), set(pi)
    for k, offset in enumerate(walk):
        logical.update(add(p, offset) for p in pi)  # 下一条真实 Pi
        if k == 0:
            continue
        previous, step = walk[k - 1], tuple(offset[i] - walk[k - 1][i] for i in range(2))
        changed = [i for i, value in enumerate(step) if value]
        if len(changed) < 2:
            continue
        axis = BRIDGE_AXIS if BRIDGE_AXIS in changed else changed[-1]
        axis_step = tuple(step[axis] if i == axis else 0 for i in range(2))
        source = pi if KEEP_END_SPECIAL else pi[:-1]
        special = [p for p in source if add(p, axis_step) not in pi_set]
        bridges.update(add(add(p, previous), axis_step) for p in special)
    return logical, bridges


def lift(points):
    a, b, den = Z_PLANE
    return {(x, y, round((a * x + b * y) / den)) for x, y in points}


def extrude(surface):
    return {add(p, offset) for offset in bres((0, 0, 0), EXTRUDE) for p in surface}


def draw2(ax, logical, bridges):
    for (x, y) in logical:
        ax.add_patch(plt.Rectangle((x, y), 1, 1, facecolor="#65b5ff", edgecolor="#17324d", linewidth=.35))
    for (x, y) in bridges:
        ax.add_patch(plt.Rectangle((x, y), 1, 1, fill=False, edgecolor="#ff7f0e", linewidth=1.5))
    ax.set_aspect("equal"); ax.autoscale(); ax.set_title("2D scan: blue=logical, orange=bridge")


def draw3(ax, points, title, color):
    mins = np.min(np.array(list(points)), axis=0)
    shifted = {tuple(np.array(p) - mins) for p in points}
    size = np.max(np.array(list(shifted)), axis=0) + 1
    voxels = np.zeros(tuple(size), dtype=bool)
    for p in shifted:
        voxels[p] = True
    ax.voxels(voxels, facecolors=color, edgecolor="#183044", linewidth=.18)
    ax.set_title(title)


logical, bridges = scan_face(SPAN, SWEEP)
surface = lift(logical | bridges)
volume = extrude(surface)
fig = plt.figure(figsize=(15, 5))
draw2(fig.add_subplot(131), logical, bridges)
draw3(fig.add_subplot(132, projection="3d"), surface, "Tilted surface", "#70bfff")
draw3(fig.add_subplot(133, projection="3d"), volume, "Bresenham-extruded volume", "#8cd5ff")
fig.tight_layout()
output = Path(__file__).with_suffix(".png")
fig.savefig(output, dpi=180)
print(f"logical={len(logical)} bridge={len(bridges)} extra={len(bridges-logical)} surface={len(surface)} volume={len(volume)}")
print(output)
