package io.github.fastformer.client.render.model;

import io.github.fastformer.fastplace.GeometryHit;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record GeometryPlanKey(
   long stateVersion,
   boolean modifierHeld,
   Vec3 eye,
   Vec3 view,
   GeometryHit candidate,
   BlockPos hoveredPoint
) {
   public GeometryPlanKey {
      Objects.requireNonNull(eye, "eye");
      Objects.requireNonNull(view, "view");
   }
}
