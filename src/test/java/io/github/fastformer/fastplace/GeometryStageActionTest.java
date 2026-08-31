package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryStageActionTest {
   @Test
   void wallRaycastDefaultsToSurfaceAndAltEmbeds() {
      BlockPos block = new BlockPos(4, 8, -2);
      GeometryHit hit = new GeometryHit(
         block,
         block,
         net.minecraft.core.Direction.EAST,
         new Vec3(5.0, 8.5, -1.5)
      );

      assertEquals(block.east(), WallWorkflow.raycastPoint(hit, false));
      assertEquals(block, WallWorkflow.raycastPoint(hit, true));

      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.WALL);
      assertTrue(new WallWorkflow().stage(session).allows(GeometryAction.SUBMODE));
   }

   @Test
   void collectingStageUsesSemanticCapabilities() {
      GeometryStage stage = GeometryStage.collecting("test")
         .allow(GeometryAction.MODE_CYCLE)
         .allow(GeometryAction.SUBMODE);

      assertTrue(stage.allows(GeometryAction.POINT_INPUT));
      assertTrue(stage.allows(GeometryAction.CANDIDATE_INPUT));
      assertTrue(stage.allows(GeometryAction.MODE_CYCLE));
      assertTrue(stage.allows(GeometryAction.SUBMODE));
      assertFalse(stage.allows(GeometryAction.SCALAR_ADJUST));
      assertFalse(stage.allows(GeometryAction.CONFIRM));
      assertFalse(stage.allows(GeometryAction.GIZMO_DRAG));
   }

   @Test
   void polyhedronAdjustmentDeclaresConfirmAndGizmo() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.enterPolyhedronAdjust(new Vec3(3.5, 0.5, 0.5));

      GeometryStage stage = new PolyhedronWorkflow().stage(session);

      assertTrue(stage.allows(GeometryAction.CONFIRM));
      assertTrue(stage.allows(GeometryAction.MODE_CYCLE));
      assertTrue(stage.allows(GeometryAction.GIZMO_DRAG));
      assertFalse(stage.allows(GeometryAction.POINT_INPUT));
   }

   @Test
   void sphereAdjustmentRejectsRotationGizmo() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.enterPolyhedronAdjust(new Vec3(4.5, 0.5, 0.5));

      assertFalse(new PolyhedronWorkflow().onGizmoDrag(
         session,
         new GeometryActionContext(null, false),
         io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.ROTATE,
         io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.Y,
         16
      ));
   }

   @Test
   void closedWallAcceptsRightClickAsConfirmation() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.WALL);
      session.addPoint(new net.minecraft.core.BlockPos(0, 0, 0));
      session.addPoint(new net.minecraft.core.BlockPos(4, 0, 0));
      session.addPoint(new net.minecraft.core.BlockPos(4, 0, 4));
      session.close();

      assertTrue(new WallWorkflow().confirmsOnRightClick(session));
   }

   @Test
   void convexHullOnlyConfirmsAfterNonCoplanarInput() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      ConvexPolyhedronWorkflow workflow = new ConvexPolyhedronWorkflow();
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.addPoint(new Vec3(4.5, 0.5, 0.5));
      session.addPoint(new Vec3(0.5, 0.5, 4.5));
      session.addPoint(new Vec3(4.5, 0.5, 4.5));

      assertFalse(workflow.stage(session).allows(GeometryAction.CONFIRM));

      session.addPoint(new Vec3(0.5, 4.5, 0.5));

      assertTrue(workflow.stage(session).allows(GeometryAction.CONFIRM));
   }

   @Test
   void wallPreviewUsesClosePathInteractionTargetAtTheStartPoint() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.WALL);
      session.addPoint(new BlockPos(0, 0, 0));
      session.addPoint(new BlockPos(4, 0, 0));
      session.addPoint(new BlockPos(4, 0, 4));

      GeometryPreviewPlan plan = new WallWorkflow().previewPlan(
         GeometryWorkflowView.from(session),
         session.points(),
         session.points().getFirst(),
         (BlockPos)null,
         Vec3.ZERO
      );

      assertEquals(1, plan.interactionTargets().size());
      GeometryInteractionTarget target = plan.interactionTargets().getFirst();
      assertEquals(GeometryInteractionTarget.TargetType.CLOSE_PATH, target.type());
      assertEquals(0, target.index());
      assertTrue(target.action(io.github.fastformer.fastplace.geometry.PointerGesture.RIGHT_CLICK) == GeometryInteractionAction.CLOSE_PATH);
      assertTrue(target.action(io.github.fastformer.fastplace.geometry.PointerGesture.RIGHT_DOUBLE_CLICK) == GeometryInteractionAction.CLOSE_PATH);
   }

   @Test
   void wallStartOnlyGetsCloseableFeedbackAfterThreePoints() {
      GeometrySession shortSession = new GeometrySession();
      shortSession.setMode(GeometryMode.WALL);
      shortSession.addPoint(new BlockPos(0, 0, 0));
      shortSession.addPoint(new BlockPos(4, 0, 0));

      GeometryPreviewPlan shortPlan = new WallWorkflow().previewPlan(
         GeometryWorkflowView.from(shortSession),
         shortSession.points(),
         shortSession.points().getFirst(),
         (BlockPos)null,
         Vec3.ZERO
      );
      assertFalse(shortPlan.controlPoints().getFirst().hovered());
      assertFalse(shortPlan.controlPoints().getFirst().feedback().changesOnHover());

      GeometrySession closeableSession = new GeometrySession();
      closeableSession.setMode(GeometryMode.WALL);
      closeableSession.addPoint(new BlockPos(0, 0, 0));
      closeableSession.addPoint(new BlockPos(4, 0, 0));
      closeableSession.addPoint(new BlockPos(4, 0, 4));

      GeometryPreviewPlan closeablePlan = new WallWorkflow().previewPlan(
         GeometryWorkflowView.from(closeableSession),
         closeableSession.points(),
         closeableSession.points().getFirst(),
         (BlockPos)null,
         Vec3.ZERO
      );
      assertTrue(closeablePlan.controlPoints().getFirst().hovered());
      assertTrue(closeablePlan.controlPoints().getFirst().feedback().changesOnHover());
   }
}
