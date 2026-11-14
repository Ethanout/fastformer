package io.github.fastformer.common.geometry.raster.face;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.LineTieBias;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Frozen input shared by classification, algorithms and their cancellation boundary. */
public record FaceRequest(List<Vec3> vertices, FillMode fill, LineTieBias tieBias, int maxBlocks,
                          BlockGenerationObserver observer) {
    public FaceRequest {
        vertices = List.copyOf(vertices);
        for (Vec3 point : vertices) {
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
                throw new IllegalArgumentException("Face vertices must be finite");
            }
        }
        fill = Objects.requireNonNull(fill, "fill");
        tieBias = tieBias == null ? LineTieBias.DEFAULT : tieBias;
        if (maxBlocks < 0) throw new IllegalArgumentException("Face block limit must not be negative");
        observer = observer == null ? BlockGenerationObserver.NONE : observer;
    }
}
