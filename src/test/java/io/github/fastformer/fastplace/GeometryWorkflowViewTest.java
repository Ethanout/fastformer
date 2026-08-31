package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryWorkflowViewTest {
   @Test
   void pointCountCannotExceedAvailableLocations() {
      GeometryWorkflowView view = view(99, 0);

      assertEquals(1, view.pointCount());
      assertEquals(0, view.selectedPointIndex());
   }

   @Test
   void negativePointCountIsNormalizedAndClearsSelection() {
      GeometryWorkflowView view = view(-4, 0);

      assertEquals(0, view.pointCount());
      assertEquals(-1, view.selectedPointIndex());
   }

   @Test
   void outOfRangeSelectionIsCleared() {
      GeometryWorkflowView view = view(1, 4);

      assertEquals(1, view.pointCount());
      assertEquals(-1, view.selectedPointIndex());
   }

   private static GeometryWorkflowView view(int pointCount, int selectedPointIndex) {
      return new GeometryWorkflowView(
         GeometryMode.WALL,
         pointCount,
         List.of(new Vec3(0.5, 0.5, 0.5)),
         List.of(ControlPointRole.PRIMARY),
         false,
         false,
         0,
         0,
         0,
         PolyhedronSizeMode.RADIUS,
         BlockPos.ZERO,
         ConePlaneMode.RADIUS,
         1.0,
         1.0,
         1.0,
         0.0,
         Vec3.ZERO,
         0.0,
         true,
         null,
         new Vec3(1.0, 1.0, 1.0),
         new Vec3(1.0, 1.0, 1.0),
         false,
         FillMode.OUTLINE,
         new Vec3(0.0, 0.0, 1.0),
         selectedPointIndex
      );
   }
}
