package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.ControlPointShape;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ConePrismPreviewTest {
   @Test
   void firstRadiusPointDoesNotPreviewTheUnconfirmedBase() {
      GeometrySession session = coneSession();
      session.addPoint(new Vec3(0.5, 0.5, 0.5));

      GeometryPreviewPlan plan = new ConePrismWorkflow().previewPlan(
         GeometryWorkflowView.from(session, false),
         session.points(),
         null,
         GeometryHit.point(new BlockPos(4, 0, 0)),
         Vec3.ZERO
      );

      assertTrue(plan.ghostBlocks().isEmpty());
      assertTrue(plan.pendingBlocks().isEmpty());
      assertEquals(2, plan.controlPoints().size());
   }

   @Test
   void completedBaseReturnsAfterPointCollection() {
      GeometrySession session = coneSession();
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.setConeRadius(4.0);
      session.addPoint(new Vec3(4.5, 0.5, 0.5));

      GeometryPreviewPlan plan = new ConePrismWorkflow().previewPlan(
         GeometryWorkflowView.from(session, false),
         session.points(),
         null,
         (GeometryHit)null,
         Vec3.ZERO
      );

      assertFalse(plan.ghostBlocks().isEmpty());
   }

   @Test
   void coneAltSelectionShowsOnlyPointMarkers() {
      GeometrySession session = coneSession();
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.setConeRadius(4.0);
      session.addPoint(new Vec3(4.5, 0.5, 0.5));

      GeometryPreviewPlan plan = new ConePrismWorkflow().previewPlan(
         GeometryWorkflowView.from(session, true),
         session.points(),
         null,
         GeometryHit.point(new BlockPos(0, 5, 0)),
         Vec3.ZERO
      );

      assertTrue(plan.ghostBlocks().isEmpty());
      assertTrue(plan.pendingBlocks().isEmpty());
      assertTrue(plan.guideLines().isEmpty());
      assertTrue(plan.guidePlanes().isEmpty());
   }

   @Test
   void coneAltPendingBoxAlignsToTheContainingBlockGrid() {
      GeometrySession session = coneSession();
      BlockPos hitBlock = new BlockPos(4, 2, -3);
      GeometryHit hit = new GeometryHit(
         hitBlock,
         hitBlock,
         Direction.WEST,
         new Vec3(4.0, 2.25, -2.75)
      );

      GeometryPreviewPlan plan = new ConePrismWorkflow().previewPlan(
         GeometryWorkflowView.from(session, true),
         session.points(),
         null,
         hit,
         Vec3.ZERO
      );

      assertEquals(new Vec3(4.0, 2.5, -2.5), hit.conePoint(true));
      assertEquals(1, plan.controlPoints().size());
      assertEquals(new Vec3(4.5, 2.5, -2.5), plan.controlPoints().getFirst().center());
      assertEquals(ControlPointShape.BLOCK, plan.controlPoints().getFirst().shape());
   }

   private static GeometrySession coneSession() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONE_PRISM);
      return session;
   }
}
