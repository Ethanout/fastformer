package io.github.fastformer.common.workspace.model;

import io.github.fastformer.core.math.Axis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkspaceTransformTest {
   @Test
   void changingOneRepeatStridePreservesTheOtherAxes() {
      WorkspaceTransform transform = new WorkspaceTransform(
         null, null, null, new BlockPos(3, 5, 7)
      );

      WorkspaceTransform updated = transform.withRepeatStride(Axis.Y, 11);

      assertEquals(new BlockPos(3, 11, 7), updated.repeatStride());
   }
}
