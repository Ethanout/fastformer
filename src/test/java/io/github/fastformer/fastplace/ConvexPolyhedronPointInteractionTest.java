package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.HoverFeedback;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConvexPolyhedronPointInteractionTest {
   @Test
   void selectedPointDrivesMoveOnlyGizmoPreview() {
      GeometrySession session = convexSession();
      session.selectControlPoint(1);

      GeometryWorkflowView view = GeometryWorkflowView.from(session);
      assertEquals(1, view.selectedPointIndex());
      assertTrue(GeometryWorkflows.get(GeometryMode.CONVEX_POLYHEDRON).stage(view).allows(GeometryAction.GIZMO_DRAG));
      GeometryPreviewPlan plan = new ConvexPolyhedronWorkflow().previewPlan(
         view,
         session.points(),
         (BlockPos)null,
         GeometryHit.point(new BlockPos(10, 0, 0)),
         Vec3.ZERO
      );

      assertNotNull(plan.gizmo());
      assertEquals(new Vec3(4.5, 0.5, 0.5), plan.gizmo().center());
      assertTrue(plan.gizmo().handles().stream().allMatch(handle -> handle.operation() == AxisGizmo.Operation.MOVE));
      assertEquals("${axis} ${operation}", plan.gizmo().textComponent().hoverTemplate().template());
      assertTrue(plan.gizmo().handles().stream().allMatch(
         handle -> handle.hoverFeedback().hoverColor() == HoverFeedback.GIZMO_HOVER_COLOR
      ));
      assertEquals(4, plan.controlPoints().size());
      assertEquals(3, plan.interactionTargets().size());
      assertIterableEquals(List.of(0, 1, 2), plan.interactionTargets().stream().map(GeometryInteractionTarget::index).toList());
      assertTrue(plan.interactionTargets().stream().allMatch(target -> target.type() == GeometryInteractionTarget.TargetType.CONTROL_POINT));
      assertTrue(plan.interactionTargets().stream().allMatch(target ->
         target.action(io.github.fastformer.fastplace.geometry.PointerGesture.LEFT_CLICK) == GeometryInteractionAction.SELECT_CONTROL_POINT
      ));
   }

   @Test
   void selectionStateClearsOnAddUndoAndModeSwitch() {
      GeometrySession session = convexSession();
      session.selectControlPoint(1);
      session.addPoint(point(8.5, 0.5, 0.5));
      assertEquals(-1, session.selectedControlPoint());

      session.selectControlPoint(1);
      session.removeOrUndo(BlockPos.ZERO);
      assertEquals(-1, session.selectedControlPoint());

      session.selectControlPoint(1);
      session.setMode(GeometryMode.WALL);
      assertEquals(-1, session.selectedControlPoint());
   }

   @Test
   void selectedPointMovesByHalfBlockStepsOnly() {
      GeometrySession session = convexSession();
      session.selectControlPoint(1);
      ConvexPolyhedronWorkflow workflow = new ConvexPolyhedronWorkflow();

      assertTrue(workflow.onGizmoDrag(session, new GeometryActionContext(null, false), AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 2));

      assertEquals(new Vec3(5.5, 0.5, 0.5), session.pointLocations().get(1));
      assertEquals(List.of(point(0.5, 0.5, 0.5), point(5.5, 0.5, 0.5), point(8.5, 0.5, 0.5)), session.pointLocations());
      assertTrue(!workflow.onGizmoDrag(session, new GeometryActionContext(null, false), AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 2));
      assertTrue(!workflow.onGizmoDrag(session, new GeometryActionContext(null, false), AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.Y, 2));
      assertTrue(!workflow.onGizmoDrag(session, new GeometryActionContext(null, false), AxisGizmo.Operation.MOVE, AxisGizmo.Axis.Z, 0));

      GeometrySession unselected = convexSession();
      assertTrue(!workflow.onGizmoDrag(unselected, new GeometryActionContext(null, false), AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 2));
   }

   @Test
   void unselectedSessionHasNoConvexGizmo() {
      GeometrySession session = convexSession();

      GeometryWorkflowView view = GeometryWorkflowView.from(session);
      GeometryPreviewPlan plan = new ConvexPolyhedronWorkflow().previewPlan(
         view,
         session.points(),
         (BlockPos)null,
         (GeometryHit)null,
         Vec3.ZERO
      );

      assertNull(plan.gizmo());
      assertTrue(GeometryWorkflows.get(GeometryMode.CONVEX_POLYHEDRON).stage(view).allows(GeometryAction.POINT_INPUT));
      assertEquals(3, plan.interactionTargets().size());
   }

   private static GeometrySession convexSession() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(4.5, 0.5, 0.5));
      session.addPoint(point(8.5, 0.5, 0.5));
      return session;
   }

   private static Vec3 point(double x, double y, double z) {
      return new Vec3(x, y, z);
   }
}
