package io.github.fastformer.client.operation.transform;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.FastFormerClientConfig;
import io.github.fastformer.client.operation.input.SelectionScrollAction;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceSymmetry;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.transform.SelectionShortcutCalculator;
import io.github.fastformer.workspace.transform.SelectionTransformCalculator;
import io.github.fastformer.workspace.transform.WorkspaceRotation;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Owns workspace transforms, resize capture, completion, and cancellation. */
public final class OperationTransforms {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ClientOperationWorkspace workspace;
   private final BooleanSupplier submissionPending;
   private final BooleanSupplier capturesPending;
   private final BooleanSupplier current;
   private final Consumer<String> failed;
   private final Runnable changed;
   private ClientOperationWorkspace.EditToken lastTooLargeGesture;
   private boolean missingLevelLogged;

   public OperationTransforms(ClientOperationWorkspace workspace, BooleanSupplier submissionPending,
      BooleanSupplier capturesPending, BooleanSupplier current, Consumer<String> failed, Runnable changed) {
      this.workspace = Objects.requireNonNull(workspace, "workspace");
      this.submissionPending = Objects.requireNonNull(submissionPending, "submissionPending");
      this.capturesPending = Objects.requireNonNull(capturesPending, "capturesPending");
      this.current = Objects.requireNonNull(current, "current");
      this.failed = Objects.requireNonNull(failed, "failed");
      this.changed = Objects.requireNonNull(changed, "changed");
   }

   public void tick(long deadline) {
      resizeCapture.tick(deadline);
      continuePointAdjustment();
   }

   public void cancel() {
      resizeCapture.cancel();
      pendingPointAdjustment = null;
      pendingResizeSelection = null;
      resizeToken = null;
      resizeFinishRequested = false;
      lastTooLargeGesture = null;
      workspace.cancelEdit();
   }

   private ClientOperationWorkspace workspace() { return workspace; }

   public void transformSelected(Minecraft minecraft, boolean flip) {
      if (minecraft == null || minecraft.player == null || submissionPending.getAsBoolean() || (workspace.editing() || capturesPending.getAsBoolean())) return;
      var owner = workspace();
      var selected = owner.selectedParts();
      if (selected.isEmpty()) return;
      var operation = flip
         ? WorkspaceSymmetry.reflection(
            SelectionShortcutCalculator.viewAxis(minecraft.player.getLookAngle()))
         : WorkspaceSymmetry.quarterTurnY();
      var result = SelectionShortcutCalculator.calculate(selected, operation);
      if (result.failureKey() != null) {
         failed.accept(result.failureKey());
         return;
      }
      if (!owner.beginEdit()) return;
      result.parts().forEach(owner::updatePart);
      owner.finishEdit();
      this.changed.run();
   }


   public boolean moveSelected(BlockPos offset) {
      return moveSelected(offset, 0, false);
   }

   public boolean scrollSelected(BlockPos offset, long timestampNanos) {
      return moveSelected(offset, timestampNanos, true);
   }

   public boolean scrollStackSelected(SelectionScrollAction.Stack stack, long timestampNanos) {
      if (capturesPending.getAsBoolean() || submissionPending.getAsBoolean()
         || workspace().locked() || workspace().editing() || workspace().selectedIds().isEmpty()) return false;
      var baseline = List.copyOf(workspace().selectedParts());
      var result = SelectionTransformCalculator.scrollStack(
         baseline, stack.axis(), stack.side(), stack.steps());
      if (result.failureKey() != null) {
         failed.accept(result.failureKey());
         return false;
      }
      if (!workspace().beginEdit()) return false;
      result.parts().forEach(workspace()::updatePart);
      boolean changed = workspace().finishScrollEdit(stack, timestampNanos,
         FastFormerClientConfig.scrollHistoryMergeNanos());
      this.changed.run();
      return changed;
   }

   private boolean moveSelected(BlockPos offset, long timestampNanos, boolean scroll) {
      if (capturesPending.getAsBoolean() || submissionPending.getAsBoolean() || offset == null || offset.equals(BlockPos.ZERO)
         || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
         return false;
      }
      Vec3 delta = Vec3.atLowerCornerOf(offset);
      for (ClientSelectionPart part : List.copyOf(workspace().selectedParts())) {
         workspace().updatePart(part.withTranslation(part.transform().translation().add(delta)));
      }
      boolean changed = scroll
         ? workspace().finishScrollEdit(offset, timestampNanos, FastFormerClientConfig.scrollHistoryMergeNanos())
         : workspace().finishEdit();
      this.changed.run();
      return changed;
   }

   public boolean canAdjustAabbFace(ClientSelectionPart part) {
      return part != null && part.canAdjustGeometry();
   }

   /** Checks whether the captured selection can be adjusted in this environment. */
   public boolean sourceSnapshotMatches(ClientSelectionPart part) {
      return sourceState(part) == SourceState.MATCHES;
   }

   /**
    * Checks adjustment readiness without replacing the captured block states.
    * The server transaction checks conflicts when the workspace is written.
    */
   public SourceState sourceState(ClientSelectionPart part) {
      if (part == null || !canAdjustAabbFace(part)) {
         return SourceState.NOT_ADJUSTABLE;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null) {
         return SourceState.LEVEL_UNAVAILABLE;
      }
      // The part already owns the immutable source snapshot captured when the
      // selection was created. Reading the live world here changes the meaning
      // of an adjustment and rejects valid states, such as unsupported fence
      // connections. The server transaction performs the authoritative
      // conflict check when it writes the operation.
      return SourceState.MATCHES;
   }

   /** Why a source check passed or failed. */
   public enum SourceState {
      PENDING,
      MATCHES,
      SOURCE_CHANGED,
      LEVEL_UNAVAILABLE,
      NOT_ADJUSTABLE
   }

   /**
    * Decides whether an adjustment gesture can start, and names the reason when it
    * cannot. READY means the source check passed. The caller starts the edit,
    * because a face drag selects and activates its part before the edit begins.
    */
   public AabbAdjustDecision aabbAdjustDecision(ClientSelectionPart part) {
      if (submissionPending.getAsBoolean()) {
         return AabbAdjustDecision.SUBMISSION_PENDING;
      }
      return switch (sourceState(part)) {
         case PENDING -> AabbAdjustDecision.CAPTURE_PENDING;
         case MATCHES -> AabbAdjustDecision.READY;
         case SOURCE_CHANGED -> AabbAdjustDecision.SOURCE_CHANGED;
         case LEVEL_UNAVAILABLE -> {
            reportMissingLevel();
            yield AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE;
         }
         case NOT_ADJUSTABLE -> AabbAdjustDecision.NO_TARGET;
      };
   }

   /** Warns one time about a missing client level, then stays quiet on later inputs. */
   private void reportMissingLevel() {
      if (missingLevelLogged) {
         return;
      }
      missingLevelLogged = true;
      LOGGER.warn("FastFormer blocked a selection adjustment because the client level is not available");
   }

   /**
    * The result of an AABB-adjust start request. READY means the client start check
    * passed. The server write-time guard is a later boundary and does not recapture
    * the edit-start source.
    */
   public enum AabbAdjustDecision {
      CAPTURE_PENDING,
      READY,
      DRAG_STARTED,
      ADJUSTED,
      UNCHANGED,
      SUBMISSION_PENDING,
      SOURCE_CHANGED,
      ENVIRONMENT_UNAVAILABLE,
      NO_TARGET,
      EDIT_UNAVAILABLE;

      /** True when the source check passed, so the caller can begin the edit. */
      public boolean ready() {
         return this == READY;
      }

      /** True when the point adjustment changed the selection. */
      public boolean adjusted() {
         return this == ADJUSTED;
      }
   }

   /**
    * The actionbar key that explains a blocked adjustment. Returns null when the
    * adjustment ran. It also returns null for ENVIRONMENT_UNAVAILABLE and
    * EDIT_UNAVAILABLE: a missing level and a live edit already have another owner,
    * so a second message does not help the player.
    */
   public String aabbAdjustFailureKey(AabbAdjustDecision decision) {
      if (decision == null) {
         return null;
      }
      return switch (decision) {
         case CAPTURE_PENDING -> "fastformer.message.operation_capture_pending";
         case SUBMISSION_PENDING -> "fastformer.message.operation_submit_pending";
         case SOURCE_CHANGED -> "fastformer.message.operation_source_changed";
         case NO_TARGET -> "fastformer.message.operation_adjust_unavailable";
         case READY, DRAG_STARTED, ADJUSTED, UNCHANGED, EDIT_UNAVAILABLE,
              ENVIRONMENT_UNAVAILABLE -> null;
      };
   }

   /** Starts a point adjustment and reports why it cannot start. */
   public AabbAdjustDecision adjustActiveAabbPoint(int mouseButton, BlockPos worldPoint) {
      return adjustAabbPoint(workspace().activeId(), mouseButton, worldPoint);
   }

   /** Validates the current source of the explicitly addressed selection before editing. */
   public AabbAdjustDecision adjustAabbPoint(int partId, int mouseButton, BlockPos worldPoint) {
      if (worldPoint == null || mouseButton < 0 || mouseButton > 2) {
         return AabbAdjustDecision.NO_TARGET;
      }
      ClientSelectionPart part = workspace().part(partId).orElse(null);
      if (workspace().editing()) return AabbAdjustDecision.EDIT_UNAVAILABLE;
      AabbAdjustDecision decision = aabbAdjustDecision(part);
      if (decision == AabbAdjustDecision.CAPTURE_PENDING) {
         if (!workspace().beginEdit()) return AabbAdjustDecision.EDIT_UNAVAILABLE;
         pendingPointAdjustment = new PendingPointAdjustment(workspace(), workspace().activeEditToken(), part, mouseButton, worldPoint.immutable());
         return AabbAdjustDecision.ADJUSTED;
      }
      if (!decision.ready()) {
         return decision;
      }
      if (!workspace().beginEdit()) {
         return AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      return capturePointAdjustment(part, mouseButton, worldPoint);
   }

   private AabbAdjustDecision capturePointAdjustment(ClientSelectionPart part, int mouseButton, BlockPos worldPoint) {
      Vec3 translation = part.transform().translation();
      BlockPos localPoint = new BlockPos(
         Mth.floor(worldPoint.getX() - translation.x),
         Mth.floor(worldPoint.getY() - translation.y),
         Mth.floor(worldPoint.getZ() - translation.z)
      );
      OperationSelectionVolume selection = mouseButton == 2
         ? part.selection().expandCuboidTo(localPoint)
         : part.selection().withCuboidPoint(mouseButton, localPoint);
      if (selection == null) {
         workspace().cancelEdit();
         return AabbAdjustDecision.UNCHANGED;
      }
      WorkspaceTransform transform = part.transform().withRepeats(
         part.transform().repeats(), BlockPos.ZERO
      );
      var owner = workspace();
      var token = owner.activeEditToken();
      boolean started = resizeCapture.start(selection, part, () -> this.current.getAsBoolean() && workspace() == owner && owner.ownsEdit(token), blocks -> {
         owner.updatePart(part.withSelection(selection).withBlocks(blocks).withTransform(transform));
         owner.finishEdit(token);
         this.changed.run();
      }, key -> { owner.cancelEdit(token); failed.accept(key); });
      if (!started) owner.cancelEdit(token);
      return started ? AabbAdjustDecision.ADJUSTED : AabbAdjustDecision.UNCHANGED;
   }

   private void continuePointAdjustment() {
      var pending = pendingPointAdjustment;
      if (pending == null) return;
      if (workspace() != pending.owner() || !pending.owner().ownsEdit(pending.token())
         || pending.owner().part(pending.part().id()).orElse(null) != pending.part()) {
         pending.owner().cancelEdit(pending.token());
         pendingPointAdjustment = null;
         return;
      }
      var decision = aabbAdjustDecision(pending.part());
      if (decision == AabbAdjustDecision.CAPTURE_PENDING) return;
      pendingPointAdjustment = null;
      if (decision.ready()) capturePointAdjustment(pending.part(), pending.button(), pending.point());
      else {
         pending.owner().cancelEdit(pending.token());
         String key = aabbAdjustFailureKey(decision);
         if (key != null) failed.accept(key);
      }
   }

   public void updateAabbFaceGesture(
      ClientOperationWorkspace.EditToken editToken,
      ClientSelectionPart baseline, int axis, boolean positiveFace, int outwardSteps
   ) {
      if (submissionPending.getAsBoolean() || !workspace().ownsEdit(editToken)
         || !canAdjustAabbFace(baseline) || axis < 0 || axis > 2) {
         return;
      }
      AABB bounds = baseline.selection().bounds();
      double minX = bounds.minX;
      double minY = bounds.minY;
      double minZ = bounds.minZ;
      double maxX = bounds.maxX;
      double maxY = bounds.maxY;
      double maxZ = bounds.maxZ;
      double signed = (positiveFace ? 1.0 : -1.0) * outwardSteps;
      switch (axis) {
         case 0 -> {
            if (positiveFace) maxX = Math.max(minX + 1.0, maxX + signed);
            else minX = Math.min(maxX - 1.0, minX + signed);
         }
         case 1 -> {
            if (positiveFace) maxY = Math.max(minY + 1.0, maxY + signed);
            else minY = Math.min(maxY - 1.0, minY + signed);
         }
         case 2 -> {
            if (positiveFace) maxZ = Math.max(minZ + 1.0, maxZ + signed);
            else minZ = Math.min(maxZ - 1.0, minZ + signed);
         }
         default -> {
            return;
         }
      }
      OperationSelectionVolume selection = new OperationSelectionVolume(
         OperationSelectionMode.CUBOID,
         new AABB(minX, minY, minZ, maxX, maxY, maxZ),
         null,
         List.of(),
         0,
         baseline.selection().point1(),
         baseline.selection().point2()
      );
      WorkspaceTransform transform = baseline.transform().withRepeats(
         baseline.transform().repeats(), BlockPos.ZERO
      );
      ClientSelectionPart current = workspace().part(baseline.id()).orElse(baseline);
      if (selection.equals(pendingResizeSelection) && java.util.Objects.equals(resizeToken, editToken)) return;
      pendingResizeSelection = selection;
      resizeToken = editToken;
      resizeFinishRequested = false;
      var owner = workspace();
      resizeCapture.start(selection, current, () -> this.current.getAsBoolean() && workspace() == owner && owner.ownsEdit(editToken), blocks -> {
         owner.updatePart(baseline.withSelection(selection).withBlocks(blocks).withTransform(transform));
         if (resizeFinishRequested) owner.finishEdit(editToken);
         pendingResizeSelection = null;
         resizeToken = null;
         this.changed.run();
      }, key -> { owner.cancelEdit(editToken); pendingResizeSelection = null; resizeToken = null; failed.accept(key); });
   }

   /** Returns a failure message once per edit, or null when no new message is needed. */
   public String updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken,
      List<ClientSelectionPart> baseline,
      boolean common,
      AxisGizmo.Operation operation,
      Axis axis,
      int direction,
      int totalSteps,
      double rotationRadians
   ) {
      return updateTransformGesture(editToken, baseline, common, operation, axis, direction, totalSteps,
         rotationRadians, WorkspaceRotation.unitAxis(axis), null);
   }

   public String updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken, List<ClientSelectionPart> baseline, boolean common,
      AxisGizmo.Operation operation, Axis axis, int direction, int totalSteps, double rotationRadians,
      Vec3 rotationAxis, Vec3 rotationPivot
   ) {
      if (submissionPending.getAsBoolean() || !workspace().ownsEdit(editToken)) {
         return null;
      }
      if (totalSteps == 0) {
         baseline.forEach(part -> {
            boolean alreadyFixed = workspace().part(part.id())
               .map(current -> current.editability() == ClientSelectionPart.Editability.LOCKED).orElse(false);
            workspace().updatePart(alreadyFixed ? part.fixed() : part);
         });
         this.changed.run();
         return null;
      }
      var result = SelectionTransformCalculator.calculate(
         baseline, common, operation, axis, direction, totalSteps, rotationRadians, rotationAxis, rotationPivot);
      if (result.failureKey() != null) {
         if (editToken.equals(lastTooLargeGesture)) return null;
         lastTooLargeGesture = editToken;
         return result.failureKey();
      }
      result.parts().forEach(workspace()::updatePart);
      this.changed.run();
      return null;
   }

   public boolean finishTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (!workspace().ownsEdit(editToken)) return false;
      if (resizeCapture.pending() && java.util.Objects.equals(resizeToken, editToken)) {
         resizeFinishRequested = true;
         return true;
      }
      boolean changed = workspace().finishEdit(editToken);
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      this.changed.run();
      return changed;
   }


   public void cancelTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (java.util.Objects.equals(resizeToken, editToken)) { resizeCapture.cancel(); resizeToken = null; pendingResizeSelection = null; }
      if (!workspace().cancelEdit(editToken)) return;
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      this.changed.run();
   }


   private final SelectionBlockCapture resizeCapture = new SelectionBlockCapture();
   private OperationSelectionVolume pendingResizeSelection;
   private ClientOperationWorkspace.EditToken resizeToken;
   private boolean resizeFinishRequested;
   private PendingPointAdjustment pendingPointAdjustment;
   private record PendingPointAdjustment(ClientOperationWorkspace owner, ClientOperationWorkspace.EditToken token,
      ClientSelectionPart part, int button, BlockPos point) { }

}
