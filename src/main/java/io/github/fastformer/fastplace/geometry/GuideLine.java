package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.Vec3;

public record GuideLine(Vec3 from, Vec3 to) {
   public GuideLine {
      from = from == null ? Vec3.ZERO : from;
      to = to == null ? Vec3.ZERO : to;
   }
}
