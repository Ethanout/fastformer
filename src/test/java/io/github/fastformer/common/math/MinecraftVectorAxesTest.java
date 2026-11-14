package io.github.fastformer.common.math;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MinecraftVectorAxesTest {
   @Test
   void signedStepsAndUnsignedAxesRemainDifferentOperations() {
      var value = new Vec3(1, -5, 3);
      assertEquals(Axis.Y, MinecraftVectors.dominantAxis(value));
      assertEquals(new Vec3(0, -1, 0), MinecraftVectors.dominantStep(value));
      assertEquals(new Vec3(0, 1, 0), MinecraftVectors.axisVector(MinecraftVectors.dominantAxis(value), 1));
      assertEquals(-5, MinecraftVectors.component(value, Axis.Y));
      assertEquals(Vec3.ZERO, MinecraftVectors.dominantStep(Vec3.ZERO));
   }
}
