package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.gizmo.GizmoRenderer;
import io.github.fastformer.client.controlpoint.ControlPointPresentation;
import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.client.operation.selection.SelectionDraftPreview;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.SmartSelectionEditView;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import io.github.fastformer.client.render.model.InitialBlockPreview;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.SelectionTargetShape;
import io.github.fastformer.common.selection.smart.SmartSelectionTopology;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.geometry.SelectionPrism;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.world.BlockSnapshot;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import net.minecraft.world.phys.Vec3;

/** Reads live selection owners only while building the frame model. */
final class SelectionPreviewModels {
   private SelectionPreviewModels() {}

   static SelectionPreviewModel capture(SelectionPreviewInput input) {
      var minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var operations = sessions.operations();
      var owner = sessions.inputSession();
      var source = operations.workspace();
      var scene = operations.interactionScene();
      var parts = source.parts();
      var blocks = new LinkedHashMap<Integer, Map<BlockPos, BlockSnapshot>>();
      var topologies = new LinkedHashMap<Integer, SmartSelectionTopology>();
      var moving = new HashSet<Integer>();
      var hoveredLabels = new HashSet<Integer>();
      var activeKeys = new LinkedHashMap<Integer, AxisGizmo.HandleKey>();
      var hidden = new HashSet<BlockPos>();
      var capture = owner.selectionGestureSnapshot().capture();
      for (var part : parts) {
         var resolved = operations.pointer().visibleBlocks(part);
         blocks.put(part.id(), resolved);
         if (part.smartEditable()) topologies.put(part.id(), source.smartTopology(part));
         if (capture != null && capture.targets().containsKey(part.id())) moving.add(part.id());
         var interaction = scene.parts().get(part.id());
         if (interaction != null && interaction.label() != null && operations.hoveredLabel(interaction.label())) hoveredLabels.add(part.id());
         var key = PointerDragSnapshotView.workspaceGizmoMatches(owner, part.id(), false) ? PointerDragSnapshotView.operationKey(owner) : null;
         if (key != null) activeKeys.put(part.id(), key);
         if (!OperationPreviewRenderer.shouldRenderPartBlocks(part) && !part.pendingDelete()) {
            for (var position : resolved.keySet()) if (SourceMaskRenderFilter.instance().hides(position)) hidden.add(position);
         }
      }
      var workspace = new SelectionPreviewModel.Workspace(parts, source.selectedIds(), source.locked(), scene, operations.visualHoverIntent(), blocks, topologies, moving,
         hoveredLabels, activeKeys, PointerDragSnapshotView.workspaceGizmoMatches(owner, 0, true) ? PointerDragSnapshotView.operationKey(owner) : null,
         owner.selectionGestureSnapshot().active(), operations.interactionState() == ClientSelectionState.ALT_FOCUSED,
         operations.selectionEdits().smartEditing(), hidden);
      boolean modifier = PointerDragSnapshotView.modifierHeld(owner);
      var remotePointer = operations.remotePointer();
      var remote = new SelectionPreviewModel.Remote(operations.operationSelectionReady(), remotePointer.operationPointUnderCrosshairIndex(),
         remotePointer.operationPrismEdgeInsertion(), remotePointer.operationCandidatePoint(), PointerDragSnapshotView.pointPlane(owner),
         PointerDragSnapshotView.pointLine(owner), PointerDragSnapshotView.pointTarget(owner), GizmoRenderer.operationGizmoAlpha(input.remoteGizmo()),
         minecraft.player == null ? Vec3.ZERO : minecraft.player.getEyePosition(),
         surface(input.snapshot(), remotePointer.operationPointUnderCrosshairIndex(), remotePointer.operationPrismEdgeInsertion(),
            operations.operationSelectionReady(), remotePointer.operationCandidatePoint()));
      boolean isolation = SmartSelectionEditView.active(minecraft);
      var smartTarget = isolation ? SmartSelectionEditView.target(minecraft, true) : null;
      return new SelectionPreviewModel(input, workspace, remote, draft(operations.selectionEdits().selectionDraft(), input.pointer()), candidate(input, minecraft, modifier, isolation),
         new SelectionPreviewModel.Modifiers(modifier, FastPlaceClientInput.controlHeld(),
            minecraft.player != null && minecraft.player.isShiftKeyDown()), new SelectionPreviewModel.Isolation(isolation,
            smartTarget == null ? null : smartTarget.pick().position().immutable()));
   }

   static SelectionPreviewModel.Surface surface(OperationPreviewPayload snapshot,
      int hoveredPoint, SelectionPrism.EdgeInsertion originalEdge,
      boolean ready, BlockPos originalCandidate) {
      List<BlockPos> points = snapshot.points();
      boolean closingPrismBase = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
         && snapshot.operationPrismBasePointCount() == 0
         && points.size() >= 3
         && hoveredPoint == 0;
      SelectionPrism.EdgeInsertion edgeInsertion = closingPrismBase ? null : originalEdge;
      BlockPos candidate = closingPrismBase
         ? null
         : edgeInsertion != null
         ? edgeInsertion.point()
         : ready ? null : originalCandidate;
      List<BlockPos> previewPoints = new ArrayList<>(points);
      if (closingPrismBase) {
         previewPoints.add(points.getFirst());
      } else if (edgeInsertion == null && candidate != null && !previewPoints.contains(candidate)) {
         previewPoints.add(candidate);
      }

      OperationSelectionVolume selection = OperationSelectionVolume.create(
         snapshot.operationSelectionMode(),
         previewPoints,
         snapshot.operationPrismBasePointCount(),
         snapshot.operationMinOffset(),
         snapshot.operationMaxOffset(),
         snapshot.operationHullInflation()
      );
      if (snapshot.operationSelectionMode() == OperationSelectionMode.CUBOID
         && points.size() >= 2 && snapshot.selectionMin() != null && snapshot.selectionMax() != null) {
         selection = OperationSelectionVolume.cuboid(
            snapshot.selectionMin(), snapshot.selectionMax(),
            points.isEmpty() ? null : points.getFirst(), points.size() < 2 ? null : points.get(1)
         );
      }
      return new SelectionPreviewModel.Surface(previewPoints, selection, candidate, edgeInsertion != null,
         ControlPointPresentation.selection(snapshot, candidate, edgeInsertion != null, hoveredPoint));
   }

   static SelectionPreviewModel.Draft draft(DraftState state, OperationInteractionIntent pointer) {
      var candidate = pointer instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null;
      var points = new ArrayList<>(state.points());
      if (!points.isEmpty() && candidate != null && !points.contains(candidate)) points.add(candidate);
      return new SelectionPreviewModel.Draft(points, SelectionDraftPreview.volume(state, candidate),
         ControlPointPresentation.draft(state, candidate));
   }

   private static SelectionPreviewModel.Candidate candidate(SelectionPreviewInput input, Minecraft minecraft, boolean modifier, boolean isolation) {
      if (isolation || !(input.pointer() instanceof OperationInteractionIntent.CreateSelection create)
         || minecraft.level == null || minecraft.player == null) return new SelectionPreviewModel.Candidate(null, null, null);
      var position = create.point();
      var state = minecraft.level.getBlockState(position);
      var shape = state.isAir() ? Shapes.block() : SelectionTargetShape.resolve(minecraft.level, position, CollisionContext.of(minecraft.player),
         InteractionRaycastRules.passesThroughFluids(minecraft.player, modifier));
      var operations = ClientSessionSystem.instance().operations();
      String hint = operations.workspace().locked() ? null : operations.selectionEdits().smartTool()
         ? Component.translatable(modifier ? "fastformer.hud.smart_hover_refine" : "fastformer.hud.smart_hover").getString()
         : create.requireHoverText().getString();
      return new SelectionPreviewModel.Candidate(position.immutable(), InitialBlockPreview.mesh(position, shape.toAabbs()), hint);
   }
}
