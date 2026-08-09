package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BoundedAabbVolumeTest {
   @Test
   void directBoundaryMatchesTheSolidForOneTwoAndThreeVoxelDimensions() {
      for (int x = 1; x <= 3; x++) {
         for (int y = 1; y <= 3; y++) {
            for (int z = 1; z <= 3; z++) {
               BoundedAabbVolume box = BoundedAabbVolume.containing(List.of(
                  new Vec3(0.5, 0.5, 0.5),
                  new Vec3(x - 0.5, y - 0.5, z - 0.5)
               ));
               Set<BlockPos> solid = box.materialize(false, 100, BlockGenerationObserver.NONE);
               Set<BlockPos> boundary = box.materialize(true, 100, BlockGenerationObserver.NONE);
               assertEquals(physicalBoundary(solid), boundary, "dimensions=" + x + "x" + y + "x" + z);
               assertEquals(boundary.size(), box.boundaryCount());
            }
         }
      }
   }

   private static Set<BlockPos> physicalBoundary(Set<BlockPos> solid) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos position : solid) {
         for (Direction direction : Direction.values()) {
            if (!solid.contains(position.relative(direction))) {
               result.add(position);
               break;
            }
         }
      }
      return result;
   }
}
