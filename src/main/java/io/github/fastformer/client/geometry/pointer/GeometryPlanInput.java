package io.github.fastformer.client.geometry.pointer;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Captured geometry facts used equally by input and drawing. */
public record GeometryPlanInput(GeometryPreviewPayload snapshot, long version, boolean modifierHeld,
   Vec3 eye, Vec3 view, GeometryHit candidate, BlockPos hoveredPoint) {
   public GeometryPlanInput {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(eye, "eye");
      Objects.requireNonNull(view, "view");
   }
}
