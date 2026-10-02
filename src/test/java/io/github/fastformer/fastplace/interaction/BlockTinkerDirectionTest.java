package io.github.fastformer.fastplace.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockTinkerDirectionTest {
   @Test
   void sideOfWestArmSelectsWestConnection() {
      var hit = new BlockHitResult(new Vec3(0.2, 0.6, 0.625), Direction.SOUTH, BlockPos.ZERO, false);
      assertEquals(Direction.WEST, ConnectionHitDirection.resolve(hit));
   }

   @Test
   void topOfEastArmSelectsEastConnection() {
      var hit = new BlockHitResult(new Vec3(0.8, 1, 0.5), Direction.UP, BlockPos.ZERO, false);
      assertEquals(Direction.EAST, ConnectionHitDirection.resolve(hit));
   }

   @Test
   void centerKeepsClickedFace() {
      var hit = new BlockHitResult(new Vec3(0.5, 0.7, 0.625), Direction.SOUTH, BlockPos.ZERO, false);
      assertEquals(Direction.SOUTH, ConnectionHitDirection.resolve(hit));
   }
}
