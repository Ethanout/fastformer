package io.github.fastformer.client.render.model;

import net.minecraft.world.phys.Vec3;

public record GridPoint(int x, int y, int z) implements Comparable<GridPoint> {
   public Vec3 vec3() {
      return new Vec3(this.x, this.y, this.z);
   }

   @Override
   public int compareTo(GridPoint other) {
      int xComparison = Integer.compare(this.x, other.x);
      if (xComparison != 0) {
         return xComparison;
      }
      int yComparison = Integer.compare(this.y, other.y);
      return yComparison != 0 ? yComparison : Integer.compare(this.z, other.z);
   }
}
