package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.PolyhedronSizeMode;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;
import io.github.fastformer.fastplace.world.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryWorkflowViewTest {
   @Test
   void pointCountCannotExceedAvailableLocations() {
      ConvexWorkflowView view = view(99, 0);

      assertEquals(1, view.pointCount());
      assertEquals(0, view.selectedPointIndex());
   }

   @Test
   void negativePointCountIsNormalizedAndClearsSelection() {
      ConvexWorkflowView view = view(-4, 0);

      assertEquals(0, view.pointCount());
      assertEquals(-1, view.selectedPointIndex());
   }

   @Test
   void outOfRangeSelectionIsCleared() {
      ConvexWorkflowView view = view(1, 4);

      assertEquals(1, view.pointCount());
      assertEquals(-1, view.selectedPointIndex());
   }

   private static ConvexWorkflowView view(int pointCount, int selectedPointIndex) {
      return new ConvexWorkflowView(new GeometryWorkflowView.Context(pointCount,
         List.of(new Vec3(0.5, 0.5, 0.5)), List.of(ControlPointRole.PRIMARY), false, false,
         FillMode.OUTLINE, new Vec3(0, 0, 1)), selectedPointIndex);
   }
}
