package io.github.fastformer.client.interaction.path;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PathCloseTargetTest {
   private final Vec3 eye = new Vec3(0.5,0.5,-5);
   private final Vec3 view = new Vec3(0,0,1);

   @Test void minimumCountsAndPriorityRemainSeparateForHoverAndDoubleClick() {
      var operation = List.of(new BlockPos(8,0,0), new BlockPos(9,0,0));
      var geometry = List.of(BlockPos.ZERO, new BlockPos(1,0,0), new BlockPos(2,0,0));
      var target = PathCloseTarget.resolve(operation, geometry, List.of(), eye, view, ray(5), new BlockPos(4,0,0));
      assertTrue(target.hoveredStart());
      assertTrue(target.doubleClickEnabled());
      assertEquals(new BlockPos(4,0,0), target.candidate());
      var preferred = List.of(new BlockPos(8,0,0), new BlockPos(9,0,0), new BlockPos(10,0,0));
      assertFalse(PathCloseTarget.resolve(preferred, geometry, List.of(), eye, view, ray(5), null).hoveredStart());
   }

   @Test void worldOcclusionPreventsHoverButKeepsDoubleClickAvailability() {
      var points = List.of(BlockPos.ZERO, new BlockPos(1,0,0), new BlockPos(2,0,0));
      var target = PathCloseTarget.resolve(points, List.of(), List.of(), eye, view, ray(-2), null);
      assertFalse(target.hoveredStart());
      assertTrue(target.doubleClickEnabled());
      var unavailable = PathCloseTarget.resolve(List.of(), List.of(), List.of(), null, null, null, null);
      assertFalse(unavailable.hoveredStart());
      assertFalse(unavailable.doubleClickEnabled());
   }

   private static LongRangeBlockRaycast.Result ray(int z) {
      return new LongRangeBlockRaycast.Result(new BlockHitResult(new Vec3(0.5,0.5,z), Direction.NORTH, new BlockPos(0,0,z), false), 20, 20, null, 1, 0);
   }
}
