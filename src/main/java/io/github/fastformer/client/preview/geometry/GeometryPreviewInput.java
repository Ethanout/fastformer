package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Geometry and pointer facts captured before plan generation; no live client lookup. */
public record GeometryPreviewInput(GeometryPreviewPayload snapshot, long version, boolean modifierHeld,
   Vec3 eye, Vec3 view, GeometryHit candidate, BlockPos hoveredPoint) {
   public GeometryPreviewInput {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(eye, "eye");
      Objects.requireNonNull(view, "view");
   }
}
