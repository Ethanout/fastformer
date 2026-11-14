package io.github.fastformer.server.geometry.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflows;
import io.github.fastformer.server.geometry.workflow.GeometryActionContext;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryWorkflowOwnershipTest {
   @Test
   void everyModeHasOneRegisteredEditorAndMatchingQueryView() {
      var registered = GeometryWorkflowEditors.registered();
      assertEquals(List.of(GeometryMode.values()), registered.stream().map(GeometryWorkflowEditor::mode).toList());
      assertThrows(UnsupportedOperationException.class, registered::clear);
      for (var workflow : registered) {
         var session = new GeometrySession();
         session.setMode(workflow.mode());
         assertEquals(workflow.stage(session), GeometryWorkflows.get(workflow.mode()).stage(GeometryWorkflowView.from(session)));
      }
   }

   @Test
   void mismatchedWorkflowRejectsEvenDefaultActionsBeforeChangingDraft() {
      var session = new GeometrySession();
      session.addPoint(BlockPos.ZERO);
      var draft = session.draftId();
      var points = session.pointLocations();
      var cone = GeometryWorkflowEditors.get(GeometryMode.CONE_PRISM);
      var context = new GeometryActionContext(null, false);

      assertThrows(IllegalArgumentException.class, () -> cone.onAddPoint(session, context, GeometryHit.point(new BlockPos(4, 0, 0))));
      assertThrows(IllegalArgumentException.class, () -> cone.onClosePath(session, context));
      assertThrows(IllegalArgumentException.class, () -> cone.clearSelection(session, PointerGesture.LEFT_CLICK));
      assertEquals(draft, session.draftId());
      assertEquals(points, session.pointLocations());
      assertFalse(session.closed());
   }

   @Test
   void selectionClearingBelongsToConvexWorkflowAndOnlyAcceptsClicks() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      session.addPoint(Vec3.ZERO);
      session.convex().selectControlPoint(0);
      var workflow = GeometryWorkflowEditors.get(session.mode());

      assertFalse(workflow.clearSelection(session, PointerGesture.RIGHT_DOUBLE_CLICK));
      assertEquals(0, session.convex().selectedControlPoint());
      assertTrue(workflow.clearSelection(session, PointerGesture.RIGHT_CLICK));
      assertEquals(-1, session.convex().selectedControlPoint());
      assertFalse(workflow.clearSelection(session, PointerGesture.LEFT_CLICK));

      session.setMode(GeometryMode.WALL);
      assertFalse(GeometryWorkflowEditors.get(session.mode()).clearSelection(session, PointerGesture.LEFT_CLICK));
   }

   @Test
   void typedPointEditsKeepSelectionInvalidationAndUndoRules() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      var state = session.convex();
      state.addPoint(Vec3.ZERO);
      state.selectControlPoint(0);
      state.addPoint(new Vec3(1, 0, 0));
      assertEquals(-1, state.selectedControlPoint());
      state.selectControlPoint(1);
      assertTrue(session.undoStep());
      assertEquals(List.of(Vec3.ZERO), state.pointLocations());
      assertEquals(-1, state.selectedControlPoint());
   }

   @Test
   void wrongWorkflowViewIsRejectedBeforeGeneratingAnotherShapesPreview() {
      var wall = GeometryWorkflowView.from(new GeometrySession());
      assertThrows(ClassCastException.class, () -> GeometryWorkflows.get(GeometryMode.CONE_PRISM).stage(wall));
   }

   @Test
   void clearingPointSelectionLeavesTheNextPointInputAvailable() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      session.addPoint(new Vec3(0.5, 0.5, 0.5));
      session.addPoint(new Vec3(4.5, 0.5, 0.5));
      session.addPoint(new Vec3(0.5, 0.5, 4.5));
      session.convex().selectControlPoint(1);
      var workflow = GeometryWorkflowEditors.get(session.mode());
      assertTrue(workflow.clearSelection(session, PointerGesture.LEFT_CLICK));
      workflow.onAddPoint(session, new GeometryActionContext(null, false), GeometryHit.point(new BlockPos(0, 4, 0)));
      assertEquals(4, session.points().size());
      assertEquals(-1, session.convex().selectedControlPoint());
   }

   @Test
   void coneLifecycleUsesInjectedPreferencesAndDoesNotSaveGizmoFrameChanges() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.CONE_PRISM);
      var saved = new ArrayList<ConePlaneMode>();
      var preferences = new GeometryActionContext.Preferences(ConePlaneMode.DIAMETER, saved::add);
      var context = new GeometryActionContext(null, false, null, Vec3.ZERO, new Vec3(0, 0, 1), preferences);
      var workflow = GeometryWorkflowEditors.get(session.mode());
      workflow.onOpen(session, context);
      assertEquals(ConePlaneMode.DIAMETER, session.cone().conePlaneMode());
      assertTrue(workflow.onCycleMode(session, context));
      assertEquals(List.of(ConePlaneMode.THREE_POINT), saved);

      session.addPoint(new Vec3(0, 0, 0));
      session.addPoint(new Vec3(4, 0, 0));
      session.addPoint(new Vec3(0, 0, 4));
      session.addPoint(new Vec3(0, 5, 0));
      boolean wasLocal = session.cone().coneGizmoLocal();
      assertTrue(workflow.onCycleMode(session, context));
      assertEquals(!wasLocal, session.cone().coneGizmoLocal());
      assertEquals(List.of(ConePlaneMode.THREE_POINT), saved);
   }
}
