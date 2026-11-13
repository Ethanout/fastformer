package io.github.fastformer.common.math;

import io.github.fastformer.core.math.Epsilon;
import io.github.fastformer.core.math.Vectors;
import net.minecraft.world.phys.Vec3;

/** Vec3 construction at the game boundary; numerical policies remain explicit. */
public final class MinecraftVectors {
   private MinecraftVectors() {}

   public static Vec3 normalize(Vec3 value) {
      return Vectors.normalize(value.x, value.y, value.z, Epsilon.LENGTH, MinecraftVectors::create);
   }

   public static Vec3 normalizeDirection(Vec3 value) {
      return Vectors.normalizeSquared(value.x, value.y, value.z,
         Epsilon.DIRECTION_LENGTH_SQUARED, MinecraftVectors::create);
   }

   public static Vec3 normalizeDirectionOrZero(Vec3 value) {
      return value == null ? Vec3.ZERO : normalizeDirection(value);
   }

   private static Vec3 create(double x, double y, double z) {
      return (Double.doubleToRawLongBits(x) | Double.doubleToRawLongBits(y)
         | Double.doubleToRawLongBits(z)) == 0 ? Vec3.ZERO : new Vec3(x, y, z);
   }
}
