package io.github.fastformer.common.math;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MinecraftVectorsTest {
   @Test void rayAndInteractionPoliciesKeepTheirExistingThresholds() {
      for (double magnitude : new double[]{0, 1.0E-9, 1.0E-7, 1.0E-5, 1.0E-4, 0.001, 3, 10000}) {
         Vec3 vector = new Vec3(magnitude, -magnitude, magnitude * 0.5);
         double length = vector.length();
         Vec3 expectedRay = length < 1.0E-7 ? Vec3.ZERO : vector.scale(1.0 / length);
         Vec3 expectedInteraction = vector.lengthSqr() < 1.0E-7 ? Vec3.ZERO : vector.normalize();
         assertEquals(expectedRay, MinecraftVectors.normalize(vector));
         assertEquals(expectedInteraction, MinecraftVectors.normalizeDirection(vector));
         assertEquals(expectedInteraction, MinecraftVectors.normalizeDirectionOrZero(vector));
      }
   }

   @Test void nullFallbackAndStrictInputsRemainDistinct() {
      assertSame(Vec3.ZERO, MinecraftVectors.normalizeDirectionOrZero(null));
      assertThrows(NullPointerException.class, () -> MinecraftVectors.normalize(null));
      assertThrows(NullPointerException.class, () -> MinecraftVectors.normalizeDirection(null));
      assertSame(Vec3.ZERO, MinecraftVectors.normalize(Vec3.ZERO));
   }
}
