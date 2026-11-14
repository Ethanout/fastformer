package io.github.fastformer.common.geometry.raster.face.classify;

import io.github.fastformer.core.math.Axis;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Integer corner facts computed once at the dispatch boundary. */
public record FaceShape(FaceClass kind, List<BlockPos> corners, Optional<Axis> normalAxis,
                        Optional<Axis> parallelAxis, boolean parallelogram) {
    public FaceShape {
        corners = corners.stream().map(BlockPos::immutable).toList();
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(normalAxis, "normalAxis");
        java.util.Objects.requireNonNull(parallelAxis, "parallelAxis");
    }
}
