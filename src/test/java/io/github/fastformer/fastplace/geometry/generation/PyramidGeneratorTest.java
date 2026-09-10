package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PyramidGeneratorTest {
   @Test
   void everyFillModeReportsRatherThanSilentlyTruncatesAtTheLimit() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 0.5),
         new Vec3(4.5, 0.5, 4.5),
         new Vec3(0.5, 0.5, 4.5)
      );
      Vec3 extrusion = new Vec3(0.0, 6.0, 0.0);
      for (FillMode mode : FillMode.values()) {
         Set<BlockPos> complete = PyramidGenerator.generate(base, extrusion, mode, 10000);

         assertFalse(complete.isEmpty(), mode.name());
         assertFalse(GenerationLimitExceeded.is(complete), mode.name());
         assertEquals(
            complete,
            PyramidGenerator.generate(base, extrusion, mode, complete.size()),
            mode.name()
         );
         assertTrue(
            GenerationLimitExceeded.is(
               PyramidGenerator.generate(base, extrusion, mode, complete.size() - 1)
            ),
            mode.name()
         );
      }
   }
}
