package io.github.fastformer.fastplace;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FastPlaceSession {
    private final List<BlockPos> points = new ArrayList<>();

    public List<BlockPos> points() {
        return Collections.unmodifiableList(points);
    }

    public FastPlaceStage stage() {
        return switch (points.size()) {
            case 0 -> FastPlaceStage.POINT;
            case 1 -> FastPlaceStage.LINE;
            case 2 -> FastPlaceStage.FACE;
            default -> FastPlaceStage.VOLUME;
        };
    }

    public boolean addOrClose(BlockPos point) {
        if (points.size() >= minimumClosingPoints() && points.getFirst().equals(point)) {
            return true;
        }
        if (points.isEmpty() || !points.getLast().equals(point)) {
            points.add(point.immutable());
        }
        return false;
    }

    public boolean undo() {
        if (points.isEmpty()) {
            return false;
        }
        points.removeLast();
        return !points.isEmpty();
    }

    private int minimumClosingPoints() {
        return stage() == FastPlaceStage.VOLUME ? 4 : 3;
    }
}
