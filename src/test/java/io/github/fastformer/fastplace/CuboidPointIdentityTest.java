package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CuboidPointIdentityTest {
   @Test
   void settingFirstPointBeforeSecondDoesNotCreateNullListOrCrash() {
      OperationSelectionVolume empty = OperationSelectionVolume.cuboid(null, null, null, null);
      OperationSelectionVolume first = empty == null
         ? new OperationSelectionVolume(OperationSelectionMode.CUBOID, new AABB(0, 0, 0, 1, 1, 1), null, List.of(), 0, null, null)
         : empty;
      OperationSelectionVolume updated = first.withCuboidPoint(0, new BlockPos(4, 5, 6));

      assertEquals(new BlockPos(4, 5, 6), updated.point1());
      assertNull(updated.point2());
      assertTrue(updated.bounds().contains(new Vec3(4.5, 5.5, 6.5)));
   }
   @Test
   void settingSecondRetainsFirstEvenWhenFirstIsTheMaximumCorner() {
      BlockPos first = new BlockPos(10, 10, 10);
      var selection = OperationSelectionVolume.create(OperationSelectionMode.CUBOID,
         List.of(first, BlockPos.ZERO), BlockPos.ZERO, BlockPos.ZERO, 0);
      var changed = selection.withCuboidPoint(1, new BlockPos(-2, 3, 4));
      assertEquals(first, changed.point1());
      assertEquals(new AABB(-2, 3, 4, 11, 11, 11), changed.bounds());
   }

   @Test
   void expansionUsesResizedBoundsAndPointEditsRecomputeFromInputPoints() {
      BlockPos first = new BlockPos(10, 10, 10);
      BlockPos second = BlockPos.ZERO;
      var resized = new OperationSelectionVolume(OperationSelectionMode.CUBOID,
         new AABB(-5, 0, 0, 21, 11, 11), null, List.of(), 0, first, second);
      var expanded = resized.expandCuboidTo(new BlockPos(0, 15, 0));
      assertEquals(first, expanded.point1());
      assertEquals(second, expanded.point2());
      assertEquals(new AABB(-5, 0, 0, 21, 16, 11), expanded.bounds());
      var edited = expanded.withCuboidPoint(1, new BlockPos(2, 2, 2));
      assertEquals(first, edited.point1());
      assertEquals(new AABB(2, 2, 2, 11, 11, 11), edited.bounds());
   }
}
