package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SmartWoodFrameTest {
   @Test
   void slopedGuideUsesItsClosestWorldAxis() {
      Set<BlockPos> outline = Set.of(new BlockPos(0, 0, 0), new BlockPos(5, 1, 0), new BlockPos(10, 2, 0));
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.Y, List.of(new BlockPos(0, 0, 0), new BlockPos(10, 2, 0))
      );

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(5, 1, 0), config));
   }

   @Test
   void aCornerPrefersTheBaseNormalAxis() {
      Set<BlockPos> corner = Set.of(
         new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, List.of());

      assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(corner, BlockPos.ZERO, config));
   }
}
