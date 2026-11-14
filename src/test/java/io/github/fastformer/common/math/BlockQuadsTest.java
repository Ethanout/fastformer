package io.github.fastformer.common.math;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BlockQuadsTest {
   @Test
   void oppositeSumsDoNotWrapAtIntegerBoundaries() {
      var a = new BlockPos(Integer.MAX_VALUE, 0, 0);
      var b = new BlockPos(-1, 0, 0);
      assertFalse(BlockQuads.isParallelogram(a, b, a, b));
      assertTrue(BlockQuads.isParallelogram(a, a, b, b));
   }

   @Test
   void identityDoesNotReplaceDegeneracyOrCornerOrderChecks() {
      assertTrue(BlockQuads.isParallelogram(List.of(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO)));
      var a = BlockPos.ZERO;
      var b = a.east();
      var c = b.south();
      var d = a.south();
      assertTrue(BlockQuads.isParallelogram(a, b, c, d));
      assertFalse(BlockQuads.isParallelogram(a, c, b, d));
      assertFalse(BlockQuads.isParallelogram(List.of(a, b, c)));
   }

   @Test
   void floatingVerticesUseTheirContainingCellsIncludingNegativeCoordinates() {
      var corners = List.of(new Vec3(-0.2, 0.1, 0.1), new Vec3(1.8, 0.2, 0.2),
         new Vec3(1.3, 0.3, 2.3), new Vec3(-0.9, 0.4, 2.4));
      assertTrue(BlockQuads.isParallelogramAfterFlooring(corners));
      assertFalse(corners.get(0).add(corners.get(2)).equals(corners.get(1).add(corners.get(3))));
   }

   @Test
   void exactCheckMatchesTheOriginalIntegerFormula() {
      var random = new Random(0x51554144L);
      for (int index = 0; index < 5000; index++) {
         BlockPos a = point(random), b = point(random), c = point(random), d = point(random);
         boolean expected = (long)a.getX() + c.getX() == (long)b.getX() + d.getX()
            && (long)a.getY() + c.getY() == (long)b.getY() + d.getY()
            && (long)a.getZ() + c.getZ() == (long)b.getZ() + d.getZ();
         assertEquals(expected, BlockQuads.isParallelogram(a, b, c, d));
      }
   }

   private static BlockPos point(Random random) {
      return new BlockPos(random.nextInt(), random.nextInt(), random.nextInt());
   }
}
