package io.github.fastformer.client.operation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.SourceBlockRenderMask;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class ClientOperationControllerTest {
   @Test
   void successfulMixedSubmissionClearsOriginalSelectionsAndSubmittedParts() throws Exception {
      ClientOperationController.onDisconnected();
      try {
         var original = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
            OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
            Map.of(), WorkspaceTransform.IDENTITY, false);
         var pasted = new ClientSelectionPart(2, ClientSelectionPart.Source.CLIPBOARD, null,
            Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(ClientOperationController.workspace().addParts(List.of(original, pasted)));
         var transferId = java.util.UUID.randomUUID();
         Field field = ClientOperationController.class.getDeclaredField("WORKSPACE_SUBMISSION");
         field.setAccessible(true);
         var tracker = (WorkspaceSubmissionTracker)field.get(null);
         tracker.begin(transferId, null, ClientOperationController.interactionScene().owner());
         ClientOperationController.workspace().setLocked(true);

         ClientOperationController.applyWorkspaceResult(
            new io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload(
               transferId, true, List.of()));

         assertEquals(0, ClientOperationController.workspace().size());
         assertTrue(ClientOperationController.workspace().selectedIds().isEmpty());
         assertFalse(ClientOperationController.workspaceSubmissionPending());
         assertFalse(ClientOperationController.workspace().locked());
      } finally {
         ClientOperationController.onDisconnected();
      }
   }

   @Test
   void altDraftPreservesSelectionUntilCompletionAndAllowsReselection() {
      ClientOperationController.onDisconnected();
      try {
         var part = new ClientSelectionPart(1, ClientSelectionPart.Source.CLIPBOARD, null,
            Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(ClientOperationController.workspace().addParts(List.of(part)));
         ClientOperationController.selectWorkspacePart(1, false);
         var selected = Set.copyOf(ClientOperationController.workspace().selectedIds());
         ClientOperationController.setAltMode(true);
         assertEquals(selected, ClientOperationController.workspace().selectedIds());
         assertTrue(ClientOperationController.handleAltCreateClick(0, BlockPos.ZERO));
         assertEquals(selected, ClientOperationController.workspace().selectedIds());
         assertEquals(1, ClientOperationController.workspace().size());
         assertTrue(ClientOperationController.handleAltCreateClick(1, new BlockPos(2, 2, 2)));
         assertEquals(2, ClientOperationController.workspace().size());
         assertFalse(ClientOperationController.workspace().selectedIds().contains(1));
         assertEquals(1, ClientOperationController.workspace().selectedIds().size());
         ClientOperationController.selectWorkspacePart(1, true);
         assertEquals(2, ClientOperationController.workspace().selectedIds().size());
         ClientOperationController.selectWorkspacePart(1, false);
         assertEquals(Set.of(1), ClientOperationController.workspace().selectedIds());
      } finally {
         ClientOperationController.onDisconnected();
      }
   }

   @Test
   void storedDraftStartsAConfirmationBoundaryOnFirstConnectionSnapshot() {
      assertTrue(ClientOperationController.shouldHoldStoredDraft(false, false, true));
      assertFalse(ClientOperationController.shouldHoldStoredDraft(true, false, true));
      assertFalse(ClientOperationController.shouldHoldStoredDraft(false, true, true));
      assertFalse(ClientOperationController.shouldHoldStoredDraft(false, false, false));
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
         ClientOperationController.failedTargetsForDraft(
            List.of(submitted), Set.of(failedTarget), List.of(submitted)
         )
      );

      ClientSelectionPart moved = submitted.withTransform(
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1.0, 0.0, 0.0))
      );
      assertEquals(
         Set.of(),
         ClientOperationController.failedTargetsForDraft(
            List.of(submitted), Set.of(failedTarget), List.of(moved)
         )
      );
   }

   @Test
   void inactiveSnapshotPreservesWorkspaceWhileSubmissionIsPending() {
      assertFalse(ClientOperationController.shouldClearWorkspaceAfterSnapshot(false, true));
   }

   @Test
   void inactiveSnapshotClearsWorkspaceAfterActiveServerOperationEnds() {
      assertTrue(ClientOperationController.shouldClearWorkspaceAfterSnapshot(true, false));
   }

   @Test
   void ordinaryInactiveSnapshotDoesNotClearLocalWorkspace() {
      assertFalse(ClientOperationController.shouldClearWorkspaceAfterSnapshot(false, false));
   }

   @Test
   void nonRetryableWorkspaceFailureMustEndTheEditableDraft() {
      assertFalse(ClientOperationController.shouldRetainWorkspaceAfterFailure(false));
      assertTrue(ClientOperationController.shouldRetainWorkspaceAfterFailure(true));
   }

   @Test
   void clearingTheWorkspaceLeavesNoAltFocusedSelectionState() {
      ClientOperationController.onDisconnected();
      ClientOperationController.setAltMode(true);
      assertEquals(ClientSelectionState.ALT_FOCUSED, ClientOperationController.interactionState());

      ClientOperationController.clearWorkspace();

      assertEquals(ClientSelectionState.UNFOCUSED, ClientOperationController.interactionState());
      ClientOperationController.onDisconnected();
   }

   @Test
   void reconnectSnapshotWaitsForConfirmationBeforeHydratingWorkspace() {
      OperationPreviewPayload payload = reconnectPayload(17L);
      ClientOperationController.onDisconnected();

      assertFalse(ClientOperationController.synchronize(payload));
      assertTrue(ClientOperationController.reconnectRestorePending());
      assertTrue(ClientOperationController.workspace().isEmpty());

      assertFalse(ClientOperationController.synchronize(payload));
      assertTrue(ClientOperationController.reconnectRestorePending());
      assertTrue(ClientOperationController.workspace().isEmpty());

      assertTrue(ClientOperationController.confirmReconnectRestore());
      assertFalse(ClientOperationController.reconnectRestorePending());
      assertFalse(ClientOperationController.workspace().isEmpty());

      ClientOperationController.onDisconnected();
   }

   @Test
   void duplicateOperationRevisionDoesNotPublishAgain() {
      ClientOperationController.onDisconnected();
      OperationPreviewPayload payload = OperationPreviewPayload.inactive(31L);

      assertTrue(ClientOperationController.synchronize(payload));
      assertFalse(ClientOperationController.synchronize(payload));

      ClientOperationController.onDisconnected();
   }

   @Test
   void leadingInactiveSnapshotKeepsWaitingForTheReconnectSession() {
      ClientOperationController.onDisconnected();

      assertTrue(ClientOperationController.synchronize(OperationPreviewPayload.inactive(4L)));
      assertFalse(ClientOperationController.reconnectRestorePending());
      assertTrue(ClientOperationController.workspace().isEmpty());

      assertFalse(ClientOperationController.synchronize(reconnectPayload(5L)));
      assertTrue(ClientOperationController.reconnectRestorePending());
      assertTrue(ClientOperationController.workspace().isEmpty());

      assertTrue(ClientOperationController.confirmReconnectRestore());
      assertFalse(ClientOperationController.reconnectRestorePending());
      assertFalse(ClientOperationController.workspace().isEmpty());

      ClientOperationController.onDisconnected();
   }

   @Test
   void settledBoundaryAppliesLaterRevisionsWithoutRestoring() {
      ClientOperationController.onDisconnected();
      assertFalse(ClientOperationController.synchronize(reconnectPayload(7L)));

      ClientOperationController.onClientTick();
      ClientOperationController.onClientTick();

      assertTrue(ClientOperationController.synchronize(reconnectPayload(8L)));
      assertFalse(ClientOperationController.reconnectRestorePending());

      ClientOperationController.onDisconnected();
   }

   @Test
   void unansweredBoundaryStopsHoldingLaterSnapshots() {
      ClientOperationController.onDisconnected();

      for (int tick = 0; tick < 200; tick++) {
         ClientOperationController.onClientTick();
      }

      assertTrue(ClientOperationController.synchronize(reconnectPayload(9L)));
      assertFalse(ClientOperationController.reconnectRestorePending());

      ClientOperationController.onDisconnected();
   }

   @Test
   void dismissingReconnectSnapshotPreventsLaterRestore() {
      ClientOperationController.onDisconnected();

      assertFalse(ClientOperationController.synchronize(reconnectPayload(23L)));
      assertTrue(ClientOperationController.reconnectRestorePending());
      ClientOperationController.dismissReconnectRestore();

      assertFalse(ClientOperationController.reconnectRestorePending());
      assertFalse(ClientOperationController.confirmReconnectRestore());
      assertTrue(ClientOperationController.workspace().isEmpty());

      ClientOperationController.onDisconnected();
   }

   @Test
   void stackGestureUsesSelectionExtentForRepeatStride() {
      ClientOperationController.onDisconnected();
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(4, 0, 0), BlockPos.ZERO, new BlockPos(4, 0, 0)
      );
      ClientSelectionPart part = new ClientSelectionPart(
         0, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );
      assertTrue(ClientOperationController.workspace().addParts(List.of(part)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      ClientOperationController.updateTransformGesture(
         edit, baseline, false, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updated = ClientOperationController.workspace().selectedParts().getFirst();
      assertEquals(5, updated.transform().repeatStride().getX());
      assertEquals(1, updated.transform().repeats().max().getX());
      ClientOperationController.onDisconnected();
   }

   @Test
   void copyingWithoutSelectableContentReportsEmptyAndDoesNotClaimAStorageFailure() {
      ClientOperationController.onDisconnected();

      assertFalse(ClientOperationController.copySelected());
      assertEquals(
         "fastformer.message.operation_copy_empty",
         ClientOperationController.lastOperationFailureKey("fallback")
      );
      // The failure key reports one result only, so a later read falls back.
      assertEquals("fallback", ClientOperationController.lastOperationFailureKey("fallback"));

      ClientOperationController.onDisconnected();
   }

   @Test
   void confirmingAReconnectRestoreRebuildsTheSourceMask() {
      ClientOperationController.onDisconnected();
      assertTrue(ClientOperationController.sourceMask().positions().isEmpty());
      assertFalse(ClientOperationController.synchronize(reconnectPayload(41L)));
      assertTrue(ClientOperationController.reconnectRestorePending());

      BlockPos source = new BlockPos(10, 64, 10);
      ClientSelectionPart moved = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(source, source, source, source),
         Map.of(source, snapshot()),
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(3.0, 0.0, 0.0)),
         false
      );
      assertTrue(ClientOperationController.workspace().addParts(List.of(moved)));
      assertTrue(ClientOperationController.sourceMask().positions().isEmpty());

      assertTrue(ClientOperationController.confirmReconnectRestore());

      assertEquals(
         SourceBlockRenderMask.maskedSourcePositions(ClientOperationController.workspace().parts()),
         ClientOperationController.sourceMask().positions()
      );
      assertTrue(ClientOperationController.sourceMask().positions().contains(source));
      ClientOperationController.onDisconnected();
   }

   @Test
   void secondStackGestureRepeatsTheWholeGroupAndKeepsPlacedCopies() {
      ClientOperationController.onDisconnected();
      // Base cell: two blocks wide. The part already holds one stack step, so the current
      // whole spans x=0..3 and that whole is this gesture's repeat unit.
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
      assertTrue(ClientOperationController.workspace().addParts(List.of(stacked)));
      assertTrue(ClientOperationController.workspace().beginEdit());
      var edit = ClientOperationController.workspace().activeEditToken();
      List<ClientSelectionPart> baseline = ClientOperationController.workspace().selectedParts();

      ClientOperationController.updateTransformGesture(
         edit, baseline, false, AxisGizmo.Operation.SCALE, AxisGizmo.Axis.X, 1, 1, Double.NaN
      );

      ClientSelectionPart updated = ClientOperationController.workspace().selectedParts().getFirst();
      // The stride field keeps the cell extent, so the placed copies at x=2 and x=3 stay.
      assertEquals(2, updated.transform().repeatStride().getX());
      // One step repeats the whole four wide group, which holds two cells.
      assertEquals(3, updated.transform().repeats().max().getX());
      Set<BlockPos> preview = WorkspacePreviewComposer.resolve(updated).keySet();
      for (int x = 0; x <= 7; x++) {
         assertTrue(preview.contains(new BlockPos(x, 0, 0)), "missing x=" + x);
      }
      ClientOperationController.onDisconnected();
   }

   @Test
   void adjustmentWithoutAnAdjustablePartReportsNoTarget() {
      ClientOperationController.onDisconnected();
      ClientSelectionPart pasted = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.CLIPBOARD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      );

      assertEquals(
         ClientOperationController.AabbAdjustDecision.NO_TARGET,
         ClientOperationController.aabbAdjustDecision(null)
      );
      assertEquals(
         ClientOperationController.AabbAdjustDecision.NO_TARGET,
         ClientOperationController.aabbAdjustDecision(pasted)
      );

      ClientOperationController.onDisconnected();
   }

   @Test
   void adjustmentWithoutAClientLevelReportsTheEnvironmentInsteadOfAChangedSource() {
      ClientOperationController.onDisconnected();
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
         ClientOperationController.AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE,
         ClientOperationController.aabbAdjustDecision(source)
      );

      ClientOperationController.onDisconnected();
   }

   @Test
   void pointAdjustmentWithoutAUsablePointReportsNoTarget() {
      ClientOperationController.onDisconnected();

      assertEquals(
         ClientOperationController.AabbAdjustDecision.NO_TARGET,
         ClientOperationController.adjustActiveAabbPoint(0, null)
      );
      assertEquals(
         ClientOperationController.AabbAdjustDecision.NO_TARGET,
         ClientOperationController.adjustActiveAabbPoint(3, BlockPos.ZERO)
      );

      ClientOperationController.onDisconnected();
   }

   @Test
   void onlyBlockedAdjustmentsThatThePlayerCanFixShowAMessage() {
      assertEquals(
         "fastformer.message.operation_submit_pending",
         ClientOperationController.aabbAdjustFailureKey(
            ClientOperationController.AabbAdjustDecision.SUBMISSION_PENDING
         )
      );
      assertEquals(
         "fastformer.message.operation_source_changed",
         ClientOperationController.aabbAdjustFailureKey(ClientOperationController.AabbAdjustDecision.SOURCE_CHANGED)
      );
      assertEquals(
         "fastformer.message.operation_adjust_unavailable",
         ClientOperationController.aabbAdjustFailureKey(ClientOperationController.AabbAdjustDecision.NO_TARGET)
      );
      // A started, finished or environment-blocked adjustment stays silent.
      for (ClientOperationController.AabbAdjustDecision decision : List.of(
         ClientOperationController.AabbAdjustDecision.READY,
         ClientOperationController.AabbAdjustDecision.DRAG_STARTED,
         ClientOperationController.AabbAdjustDecision.ADJUSTED,
         ClientOperationController.AabbAdjustDecision.UNCHANGED,
         ClientOperationController.AabbAdjustDecision.EDIT_UNAVAILABLE,
         ClientOperationController.AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE
      )) {
         assertNull(ClientOperationController.aabbAdjustFailureKey(decision), () -> "unexpected message for " + decision);
      }
      assertNull(ClientOperationController.aabbAdjustFailureKey(null));
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
