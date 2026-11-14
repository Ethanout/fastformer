package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.session.WallGeometryState;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.fastplace.workflow.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TypedGeometrySessionTest {
   @Test
   void wrongShapeAccessFailsAtTheOwnerBoundaryInsteadOfSilentlyIgnoringEdits() {
      var session = new GeometrySession();
      assertInstanceOf(WallGeometryState.class, session.shape());
      assertThrows(IllegalStateException.class, session::cone);
      assertThrows(IllegalStateException.class, session::polyhedron);
      assertThrows(IllegalStateException.class, session::compound);
      assertThrows(IllegalStateException.class, session::convex);
      assertTrue(session.points().isEmpty());
   }

   @Test
   void everyModePublishesOnlyItsOwnWorkflowView() {
      var session = new GeometrySession();
      for (GeometryMode mode : GeometryMode.values()) {
         session.setMode(mode);
         session.addPoint(BlockPos.ZERO);
         var view = GeometryWorkflowView.from(session);
         assertEquals(mode, view.mode());
         assertEquals(session.pointLocations(), view.pointLocations());
         switch (mode) {
            case WALL -> assertInstanceOf(WallWorkflowView.class, view);
            case CONE_PRISM -> assertInstanceOf(ConeWorkflowView.class, view);
            case POLYHEDRON -> assertInstanceOf(PolyhedronWorkflowView.class, view);
            case COMPOUND -> assertInstanceOf(CompoundWorkflowView.class, view);
            case CONVEX_POLYHEDRON -> assertInstanceOf(ConvexWorkflowView.class, view);
         }
      }
   }

   @Test
   void viewFreezesPointsAndRotationWhenTheLiveShapeContinuesEditing() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(BlockPos.ZERO);
      var view = (PolyhedronWorkflowView) GeometryWorkflowView.from(session);
      double[] rotation = view.rotation();
      rotation[0] = 90;
      session.polyhedron().setEulerDegrees(0, 90, 0);
      session.addPoint(new BlockPos(3, 0, 0));
      assertEquals(1, view.pointCount());
      assertEquals(1, view.rotation()[0]);
      assertThrows(UnsupportedOperationException.class, () -> view.pointLocations().clear());
   }

   @Test
   void wrongWorkflowViewIsRejectedBeforeGeneratingAnotherShapesPreview() {
      var wall = GeometryWorkflowView.from(new GeometrySession());
      assertThrows(ClassCastException.class, () -> new ConePrismWorkflow().stage(wall));
   }
}
