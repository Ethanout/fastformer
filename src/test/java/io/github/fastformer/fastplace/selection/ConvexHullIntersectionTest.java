package io.github.fastformer.fastplace.selection;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConvexHullIntersectionTest {
   @Test void clipsOutsideCellsAndKeepsInteriorAndBoundaryCells() {
      var points = List.of(BlockPos.ZERO, new BlockPos(10,0,0), new BlockPos(0,10,0), new BlockPos(0,0,10));
      var hull = OperationSelectionVolume.create(OperationSelectionMode.CONVEX_HULL, points, BlockPos.ZERO, BlockPos.ZERO, 0);
      assertFalse(hull.intersects(new AABB(9,9,9,10,10,10)));
      assertTrue(hull.intersects(new AABB(1,1,1,2,2,2)));
      assertTrue(hull.intersects(new AABB(0,0,0,1,1,1)));
      assertTrue(hull.intersects(new AABB(3,3,3,4,4,4)));
      assertFalse(hull.intersects(new AABB(5,5,5,6,6,6)));
      var inflated = OperationSelectionVolume.create(OperationSelectionMode.CONVEX_HULL, points, BlockPos.ZERO, BlockPos.ZERO, 3);
      assertTrue(inflated.intersects(new AABB(5,5,5,6,6,6)));
   }
}
