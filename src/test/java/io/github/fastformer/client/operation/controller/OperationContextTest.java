package io.github.fastformer.client.operation.controller;

import io.github.fastformer.client.operation.transform.OperationTransforms;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.core.math.Axis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.client.operation.render.SourceBlockRenderMask;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OperationContextTest {
   @Test
   void rejectedSubmissionRemembersImmutableConflictPositionsWithoutMutatingThePayload() {
      var selection = new io.github.fastformer.client.operation.selection.ClientSelectionSession();
      var tracker = new WorkspaceSubmissionTracker(600, 40);
      var operations = new OperationContext(selection,
         new io.github.fastformer.client.operation.clipboard.OperationClipboardRepository(), tracker);
      assertTrue(operations.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD))));
      var transfer = java.util.UUID.randomUUID();
      tracker.begin(transfer, null, selection.interactionOwnerId());
      operations.workspace().setLocked(true);
      var target = new BlockPos(4, 5, 6);
      var rejected = new io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload(
         transfer, false, List.of(), List.of(target));

      operations.applyWorkspaceResult(rejected);

      assertEquals(Set.of(target), operations.failedWorkspaceTargets());
      assertEquals(List.of(target), rejected.failedTargetPositions());
      assertFalse(operations.workspace().locked());
      assertFalse(operations.workspaceSubmissionPending());
      assertFalse(operations.workspace().isEmpty());
      operations.clearWorkspace();
   }

   @Test
   void disconnectClearsSubmissionAndOpenWorkspaceEdit() {
      var selection = new io.github.fastformer.client.operation.selection.ClientSelectionSession();
      var submission = new WorkspaceSubmissionTracker(600, 40);
      var operations = new OperationContext(selection,
         new io.github.fastformer.client.operation.clipboard.OperationClipboardRepository(), submission);
      operations.workspace().addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      assertTrue(operations.workspace().beginEdit());
      submission.begin(java.util.UUID.randomUUID(), null, selection.interactionOwnerId());
      assertTrue(operations.workspaceSubmissionPending());

      operations.onDisconnected();

      assertFalse(operations.workspaceSubmissionPending());
      assertFalse(operations.workspaceEditInProgress());
      assertFalse(submission.pending());
      assertTrue(operations.sourceMask().positions().isEmpty());
   }

   @Test
   void withdrawingTheOnlyDraftPointEndsTheSelectionSessionAndUndoRestoresIt() {
      ClientSessionSystem.instance().operations().onDisconnected();
      try {
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(1, BlockPos.ZERO));
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(0, null));

         assertFalse(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
         assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());
         assertTrue(ClientSessionSystem.instance().operations().sourceMask().positions().isEmpty());

         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().undo());
         assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertEquals(List.of(BlockPos.ZERO), ClientSessionSystem.instance().operations().selectionEdits().selectionDraft().points());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void withdrawingANewDraftKeepsThePreviousSelection() {
      ClientSessionSystem.instance().operations().onDisconnected();
      try {
         var old = ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD);
         assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(old)));
         var parts = List.copyOf(ClientSessionSystem.instance().operations().workspace().parts());
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleAltCreateClick(1, BlockPos.ZERO));
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleAltCreateClick(0, null));

         assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertFalse(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
         assertEquals(parts, ClientSessionSystem.instance().operations().workspace().parts());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void withdrawingBothCompletedCuboidPointsEndsTheSession() {
      ClientSessionSystem.instance().operations().onDisconnected();
      try {
         var second = new BlockPos(2, 2, 2);
         var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
            OperationSelectionVolume.cuboid(BlockPos.ZERO, second, BlockPos.ZERO, second),
            Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(part)));

         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(0, null));
         assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertEquals(List.of(BlockPos.ZERO), ClientSessionSystem.instance().operations().selectionEdits().selectionDraft().points());
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(0, null));
         assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertFalse(ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void removingTheLastSmartRegionEndsTheSession() {
      ClientSessionSystem.instance().operations().onDisconnected();
      try {
         var session = ClientSessionSystem.instance().selectionSession();
         session.setSelectionMode(OperationSelectionMode.SMART);
         var cells = Map.of(BlockPos.ZERO, snapshot());
         assertTrue(io.github.fastformer.client.operation.selection.SmartSelectionEdits.add(
            ClientSessionSystem.instance().operations().workspace(), cells));
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleCreateClick(0, BlockPos.ZERO));

         assertFalse(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().undo());
         assertTrue(ClientSessionSystem.instance().operations().selectionSessionActive());
         assertEquals(cells, ClientSessionSystem.instance().operations().workspace().parts().getFirst().blocks());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void successfulMixedSubmissionClearsOriginalSelectionsAndSubmittedParts() {
      var selection = new io.github.fastformer.client.operation.selection.ClientSelectionSession();
      var tracker = new WorkspaceSubmissionTracker(600, 40);
      var operations = new OperationContext(selection,
         new io.github.fastformer.client.operation.clipboard.OperationClipboardRepository(), tracker);
      try {
         var original = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
            OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
            Map.of(), WorkspaceTransform.IDENTITY, false);
         var pasted = new ClientSelectionPart(2, ClientSelectionPart.Source.CLIPBOARD, null,
            Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(operations.workspace().addParts(List.of(original, pasted)));
         var transferId = java.util.UUID.randomUUID();
         tracker.begin(transferId, null, operations.interactionScene().owner());
         operations.workspace().setLocked(true);

         operations.applyWorkspaceResult(
            new io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload(
               transferId, true, List.of()));

         assertEquals(0, operations.workspace().size());
         assertTrue(operations.workspace().selectedIds().isEmpty());
         assertFalse(operations.workspaceSubmissionPending());
         assertFalse(operations.workspace().locked());
      } finally {
         operations.onDisconnected();
      }
   }

   @Test
   void altDraftPreservesSelectionUntilCompletionAndAllowsReselection() {
      ClientSessionSystem.instance().operations().onDisconnected();
      try {
         var part = new ClientSelectionPart(1, ClientSelectionPart.Source.CLIPBOARD, null,
            Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(part)));
         ClientSessionSystem.instance().operations().selectWorkspacePart(1, false);
         var selected = Set.copyOf(ClientSessionSystem.instance().operations().workspace().selectedIds());
         ClientSessionSystem.instance().operations().setAltMode(true);
         assertEquals(selected, ClientSessionSystem.instance().operations().workspace().selectedIds());
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleAltCreateClick(1, BlockPos.ZERO));
         assertEquals(selected, ClientSessionSystem.instance().operations().workspace().selectedIds());
         assertEquals(1, ClientSessionSystem.instance().operations().workspace().size());
         assertTrue(ClientSessionSystem.instance().operations().selectionEdits().handleAltCreateClick(1, new BlockPos(2, 2, 2)));
         assertEquals(2, ClientSessionSystem.instance().operations().workspace().size());
         assertFalse(ClientSessionSystem.instance().operations().workspace().selectedIds().contains(1));
         assertEquals(1, ClientSessionSystem.instance().operations().workspace().selectedIds().size());
         ClientSessionSystem.instance().operations().selectWorkspacePart(1, true);
         assertEquals(2, ClientSessionSystem.instance().operations().workspace().selectedIds().size());
         ClientSessionSystem.instance().operations().selectWorkspacePart(1, false);
         assertEquals(Set.of(1), ClientSessionSystem.instance().operations().workspace().selectedIds());
      } finally {
         ClientSessionSystem.instance().operations().onDisconnected();
      }
   }

   @Test
   void failedTargetsOnlyApplyToTheDraftThatProducedThem() {
      ClientSelectionPart submitted = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      );
      BlockPos failedTarget = new BlockPos(4, 5, 6);

      assertEquals(
         Set.of(failedTarget),
         OperationContext.failedTargetsForDraft(
            List.of(submitted), Set.of(failedTarget), List.of(submitted)
         )
      );

      ClientSelectionPart moved = submitted.withTransform(
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1.0, 0.0, 0.0))
      );
      assertEquals(
         Set.of(),
         OperationContext.failedTargetsForDraft(
            List.of(submitted), Set.of(failedTarget), List.of(moved)
         )
      );
   }

   @Test
   void inactiveSnapshotPreservesWorkspaceWhileSubmissionIsPending() {
      assertFalse(OperationContext.shouldClearWorkspaceAfterSnapshot(false, true));
   }

   @Test
   void inactiveSnapshotClearsWorkspaceAfterActiveServerOperationEnds() {
      assertTrue(OperationContext.shouldClearWorkspaceAfterSnapshot(true, false));
   }

   @Test
   void ordinaryInactiveSnapshotDoesNotClearLocalWorkspace() {
      assertFalse(OperationContext.shouldClearWorkspaceAfterSnapshot(false, false));
   }

   @Test
   void nonRetryableWorkspaceFailureMustEndTheEditableDraft() {
      assertFalse(OperationContext.shouldRetainWorkspaceAfterFailure(false));
      assertTrue(OperationContext.shouldRetainWorkspaceAfterFailure(true));
   }

   @Test
   void clearingTheWorkspaceLeavesNoAltFocusedSelectionState() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSessionSystem.instance().operations().setAltMode(true);
      assertEquals(ClientSelectionState.ALT_FOCUSED, ClientSessionSystem.instance().operations().interactionState());

      ClientSessionSystem.instance().operations().clearWorkspace();

      assertEquals(ClientSelectionState.UNFOCUSED, ClientSessionSystem.instance().operations().interactionState());
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void reconnectSnapshotWaitsForConfirmationBeforeHydratingWorkspace() {
      OperationPreviewPayload payload = reconnectPayload(17L);
      ClientSessionSystem.instance().operations().onDisconnected();

      assertFalse(ClientSessionSystem.instance().operations().synchronize(payload));
      assertTrue(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());

      assertFalse(ClientSessionSystem.instance().operations().synchronize(payload));
      assertTrue(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());

      assertTrue(ClientSessionSystem.instance().operations().confirmReconnectRestore());
      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertFalse(ClientSessionSystem.instance().operations().workspace().isEmpty());

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void duplicateOperationRevisionDoesNotPublishAgain() {
      ClientSessionSystem.instance().operations().onDisconnected();
      OperationPreviewPayload payload = OperationPreviewPayload.inactive(31L);

      assertTrue(ClientSessionSystem.instance().operations().synchronize(payload));
      assertFalse(ClientSessionSystem.instance().operations().synchronize(payload));

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void leadingInactiveSnapshotKeepsWaitingForTheReconnectSession() {
      ClientSessionSystem.instance().operations().onDisconnected();

      assertTrue(ClientSessionSystem.instance().operations().synchronize(OperationPreviewPayload.inactive(4L)));
      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());

      assertFalse(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(5L)));
      assertTrue(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());

      assertTrue(ClientSessionSystem.instance().operations().confirmReconnectRestore());
      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertFalse(ClientSessionSystem.instance().operations().workspace().isEmpty());

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void settledBoundaryAppliesLaterRevisionsWithoutRestoring() {
      ClientSessionSystem.instance().operations().onDisconnected();
      assertFalse(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(7L)));

      ClientSessionSystem.instance().operations().onClientTick();
      ClientSessionSystem.instance().operations().onClientTick();

      assertTrue(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(8L)));
      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void unansweredBoundaryStopsHoldingLaterSnapshots() {
      ClientSessionSystem.instance().operations().onDisconnected();

      for (int tick = 0; tick < 200; tick++) {
         ClientSessionSystem.instance().operations().onClientTick();
      }

      assertTrue(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(9L)));
      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void dismissingReconnectSnapshotPreventsLaterRestore() {
      ClientSessionSystem.instance().operations().onDisconnected();

      assertFalse(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(23L)));
      assertTrue(ClientSessionSystem.instance().operations().reconnectRestorePending());
      ClientSessionSystem.instance().operations().dismissReconnectRestore();

      assertFalse(ClientSessionSystem.instance().operations().reconnectRestorePending());
      assertFalse(ClientSessionSystem.instance().operations().confirmReconnectRestore());
      assertTrue(ClientSessionSystem.instance().operations().workspace().isEmpty());

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void stackGestureUsesSelectionExtentForRepeatStride() {
      ClientSessionSystem.instance().operations().onDisconnected();
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(4, 0, 0), BlockPos.ZERO, new BlockPos(4, 0, 0)
      );
      ClientSelectionPart part = new ClientSelectionPart(
         0, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(part)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, false, AxisGizmo.Operation.SCALE, Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updated = ClientSessionSystem.instance().operations().workspace().selectedParts().getFirst();
      assertEquals(5, updated.transform().repeatStride().getX());
      assertEquals(1, updated.transform().repeats().max().getX());
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void confirmingAReconnectRestoreRebuildsTheSourceMask() {
      ClientSessionSystem.instance().operations().onDisconnected();
      assertTrue(ClientSessionSystem.instance().operations().sourceMask().positions().isEmpty());
      assertFalse(ClientSessionSystem.instance().operations().synchronize(reconnectPayload(41L)));
      assertTrue(ClientSessionSystem.instance().operations().reconnectRestorePending());

      BlockPos source = new BlockPos(10, 64, 10);
      ClientSelectionPart moved = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(source, source, source, source),
         Map.of(source, snapshot()),
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(3.0, 0.0, 0.0)),
         false
      );
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(moved)));
      assertTrue(ClientSessionSystem.instance().operations().sourceMask().positions().isEmpty());

      assertTrue(ClientSessionSystem.instance().operations().confirmReconnectRestore());

      assertEquals(
         SourceBlockRenderMask.maskedSourcePositions(ClientSessionSystem.instance().operations().workspace().parts()),
         ClientSessionSystem.instance().operations().sourceMask().positions()
      );
      assertTrue(ClientSessionSystem.instance().operations().sourceMask().positions().contains(source));
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void secondStackGestureKeepsItsOriginalCellSizeAndPlacedCopies() {
      ClientSessionSystem.instance().operations().onDisconnected();
      // The displayed part spans x=0..3, but its original repeat unit stays two blocks wide.
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(1, 0, 0), BlockPos.ZERO, new BlockPos(1, 0, 0)
      );
      ClientSelectionPart stacked = new ClientSelectionPart(
         0,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(BlockPos.ZERO, snapshot(), new BlockPos(1, 0, 0), snapshot()),
         new WorkspaceTransform(
            Vec3.ZERO, Vec3.ZERO,
            new OperationStackRegion(BlockPos.ZERO, new BlockPos(1, 0, 0)),
            new BlockPos(2, 0, 0)
         ),
         false
      );
      assertTrue(ClientSessionSystem.instance().operations().workspace().addParts(List.of(stacked)));
      assertTrue(ClientSessionSystem.instance().operations().workspace().beginEdit());
      var edit = ClientSessionSystem.instance().operations().workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientSessionSystem.instance().operations().workspace().selectedParts();

      ClientSessionSystem.instance().operations().transforms().updateTransformGesture(
         edit, baseline, false, AxisGizmo.Operation.SCALE, Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updated = ClientSessionSystem.instance().operations().workspace().selectedParts().getFirst();
      // The stride field keeps the cell extent, so the placed copies at x=2 and x=3 stay.
      assertEquals(2, updated.transform().repeatStride().getX());
      // One step adds one original cell.
      assertEquals(2, updated.transform().repeats().max().getX());
      Set<BlockPos> preview = WorkspacePreviewComposer.resolve(updated).keySet();
      assertEquals(6, preview.size());
      for (int x = 0; x <= 5; x++) {
         assertTrue(preview.contains(new BlockPos(x, 0, 0)), "missing x=" + x);
      }
      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void adjustmentWithoutAnAdjustablePartReportsNoTarget() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSelectionPart pasted = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.CLIPBOARD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      );

      assertEquals(
         OperationTransforms.AabbAdjustDecision.NO_TARGET,
         ClientSessionSystem.instance().operations().transforms().aabbAdjustDecision(null)
      );
      assertEquals(
         OperationTransforms.AabbAdjustDecision.NO_TARGET,
         ClientSessionSystem.instance().operations().transforms().aabbAdjustDecision(pasted)
      );

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void adjustmentWithoutAClientLevelReportsTheEnvironmentInsteadOfAChangedSource() {
      ClientSessionSystem.instance().operations().onDisconnected();
      ClientSelectionPart source = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      );

      // A unit test has no client level, so the source check cannot run. The outcome
      // must not claim that a source block changed.
      assertEquals(
         OperationTransforms.AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE,
         ClientSessionSystem.instance().operations().transforms().aabbAdjustDecision(source)
      );

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void pointAdjustmentWithoutAUsablePointReportsNoTarget() {
      ClientSessionSystem.instance().operations().onDisconnected();

      assertEquals(
         OperationTransforms.AabbAdjustDecision.NO_TARGET,
         ClientSessionSystem.instance().operations().transforms().adjustActiveAabbPoint(0, null)
      );
      assertEquals(
         OperationTransforms.AabbAdjustDecision.NO_TARGET,
         ClientSessionSystem.instance().operations().transforms().adjustActiveAabbPoint(3, BlockPos.ZERO)
      );

      ClientSessionSystem.instance().operations().onDisconnected();
   }

   @Test
   void onlyBlockedAdjustmentsThatThePlayerCanFixShowAMessage() {
      assertEquals(
         "fastformer.message.operation_submit_pending",
         ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(
            OperationTransforms.AabbAdjustDecision.SUBMISSION_PENDING
         )
      );
      assertEquals(
         "fastformer.message.operation_source_changed",
         ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(OperationTransforms.AabbAdjustDecision.SOURCE_CHANGED)
      );
      assertEquals(
         "fastformer.message.operation_adjust_unavailable",
         ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(OperationTransforms.AabbAdjustDecision.NO_TARGET)
      );
      // A started, finished or environment-blocked adjustment stays silent.
      for (OperationTransforms.AabbAdjustDecision decision : List.of(
         OperationTransforms.AabbAdjustDecision.READY,
         OperationTransforms.AabbAdjustDecision.DRAG_STARTED,
         OperationTransforms.AabbAdjustDecision.ADJUSTED,
         OperationTransforms.AabbAdjustDecision.UNCHANGED,
         OperationTransforms.AabbAdjustDecision.EDIT_UNAVAILABLE,
         OperationTransforms.AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE
      )) {
         assertNull(ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(decision), () -> "unexpected message for " + decision);
      }
      assertNull(ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(null));
   }

   private static ClientBlockSnapshot snapshot() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }

   private static OperationPreviewPayload reconnectPayload(long revision) {
      return OperationPreviewPayload.active(
         revision,
         true,
         true,
         List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 2)),
         new BlockPos(1, 64, 1),
         new BlockPos(2, 64, 2),
         BlockPos.ZERO,
         BlockPos.ZERO,
         OperationSelectionMode.CUBOID,
         0,
         -1,
         0,
         OperationMode.MOVE,
         OperationStageMode.TRANSFORM,
         BlockPos.ZERO,
         BlockPos.ZERO,
         BlockPos.ZERO,
         Vec3.ZERO,
         false,
         false,
         false
      );
   }
}
