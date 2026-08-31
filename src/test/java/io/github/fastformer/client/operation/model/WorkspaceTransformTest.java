package io.github.fastformer.client.operation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WorkspaceTransformTest {
   @Test
   void changingOneRepeatStridePreservesTheOtherAxes() {
      WorkspaceTransform transform = new WorkspaceTransform(
         null, null, null, new BlockPos(3, 5, 7)
      );

      WorkspaceTransform updated = transform.withRepeatStride(AxisGizmo.Axis.Y, 11);

      assertEquals(new BlockPos(3, 11, 7), updated.repeatStride());
   }
}
