package io.github.fastformer.fastplace.geometry;

import java.util.List;
import net.minecraft.world.phys.Vec3;

public record GuidePlane(Vec3 center, Vec3 normal, List<Vec3> bounds, boolean showWhenAxisAligned) {
   public GuidePlane(Vec3 center, Vec3 normal, List<Vec3> bounds) {
      this(center, normal, bounds, false);
   }

   public GuidePlane {
      center = center == null ? Vec3.ZERO : center;
      normal = normal == null ? Vec3.ZERO : normal;
      bounds = bounds == null ? List.of() : List.copyOf(bounds);
   }
}
