package io.github.fastformer.client.gizmo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OperationGizmoPresentationTest {
   @Test
   void centerProximitySelectsFullOrHalfOpacity() {
      assertEquals(1.0F, OperationGizmoPresentation.alpha(true));
      assertEquals(0.5F, OperationGizmoPresentation.alpha(false));
   }

   @Test
   void stageDeterminesHandlesWithoutAConfirmationGate() {
      assertEquals(
         List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE),
         OperationGizmoPresentation.operations(OperationStageMode.TRANSFORM)
      );
      assertEquals(List.of(), OperationGizmoPresentation.operations(OperationStageMode.SWEEP));
   }

   @Test
   void previewOffsetsEnumerateTheCompleteStackRegion() {
      assertEquals(
         List.of(
            new BlockPos(-1, 0, 0), new BlockPos(0, 0, 0),
            new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)
         ),
         OperationGizmoPresentation.stackOffsets(
            new BlockPos(-1, 0, 0), new BlockPos(2, 0, 0), 10
         )
      );
   }
}
