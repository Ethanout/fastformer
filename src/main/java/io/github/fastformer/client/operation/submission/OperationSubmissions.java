package io.github.fastformer.client.operation.submission;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.common.mode.SessionActivity;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/** Owns preparation, input capture, result settlement, and timeout for one editor's submission. */
public final class OperationSubmissions {
   public record Effects(Runnable clearWorkspace, Runnable changed, Runnable completeSources,
      Consumer<ClientOperationDraft.Remainder> remainder, Consumer<String> message,
      Consumer<List<BlockPos>> conflicts, Runnable clearSelectionPreview) {
      public Effects {
         Objects.requireNonNull(clearWorkspace); Objects.requireNonNull(changed);
         Objects.requireNonNull(completeSources); Objects.requireNonNull(remainder);
         Objects.requireNonNull(message); Objects.requireNonNull(conflicts);
         Objects.requireNonNull(clearSelectionPreview);
      }
   }

   private final ClientSelectionSession session;
   private final Supplier<OperationDraftIdentity> identity;
   private final BooleanSupplier capturePending;
   private final Supplier<? extends SubmissionStorage> storage;
   private final SubmissionEnvironment environment;
   private final Effects effects;
   private final WorkspaceSubmissionTracker tracker = new WorkspaceSubmissionTracker(600, 40);
   private SubmissionScope scope = new SubmissionScope.WholeWorkspace();

   public OperationSubmissions(ClientSelectionSession session, Supplier<OperationDraftIdentity> identity,
      BooleanSupplier capturePending, Supplier<? extends SubmissionStorage> storage, SubmissionEnvironment environment, Effects effects) {
      this.session = Objects.requireNonNull(session);
      this.identity = Objects.requireNonNull(identity);
      this.capturePending = Objects.requireNonNull(capturePending);
      this.storage = Objects.requireNonNull(storage);
      this.environment = Objects.requireNonNull(environment);
      this.effects = Objects.requireNonNull(effects);
   }

   public boolean pending() { return tracker.pending(session.interactionOwnerId()); }
   public UUID transferId() { return tracker.transferId(); }
   public OperationDraftIdentity identity() { return tracker.identity(); }

   public SubmissionActionResult submitWorkspace() {
      return submit(WorkspaceContentPreparer.submissionParts(session.workspace().parts()), new SubmissionScope.WholeWorkspace());
   }

   public SubmissionActionResult submit(List<OperationWorkspacePlan.Part> parts, SubmissionScope requestedScope) {
      Objects.requireNonNull(requestedScope, "scope");
      if (pending() || capturePending.getAsBoolean()) return SubmissionActionResult.PENDING;
      var workspace = session.workspace();
      if (workspace.editing() || !environment.available() || workspace.isEmpty() && !session.hasDraft()) {
         return SubmissionActionResult.INVALID;
      }
      if (parts.isEmpty()) return SubmissionActionResult.NO_CONTENT;
      UUID transferId = null;
      try {
         var plan = new OperationWorkspacePlan(parts);
         if (!environment.supported()) return SubmissionActionResult.UNAVAILABLE;
         transferId = UUID.randomUUID();
         if (!environment.begin(transferId)) return SubmissionActionResult.STATE_CHANGED;
         var origin = origin();
         var draftIdentity = origin == OperationSubmissionOrigin.SERVER_SELECTION ? identity.get() : null;
         if (origin == OperationSubmissionOrigin.SERVER_SELECTION && draftIdentity == null) {
            environment.abort(transferId);
            return SubmissionActionResult.STATE_CHANGED;
         }
         var connectionOwned = environment.freezeOwnership();
         tracker.begin(transferId, draftIdentity, session.interactionOwnerId());
         scope = requestedScope;
         effects.remainder().accept(scope.remainder(session));
         workspace.setLocked(true);
         UUID expectedTransfer = transferId;
         UUID expectedOwner = session.interactionOwnerId();
         BooleanSupplier owned = () -> tracker.accepts(expectedTransfer, expectedOwner)
            && Objects.equals(expectedOwner, session.interactionOwnerId()) && connectionOwned.getAsBoolean();
         boolean queued = storage.get().prepareSubmission(draftIdentity, origin, transferId, plan, owned, send -> {
            // Storage implementations also check ownership. Keep this guard here so a stale
            // completion can never unlock or abort a newer submission of the same editor.
            if (!owned.getAsBoolean()) return;
            if (send == null) { fail(expectedTransfer, null); return; }
            try { send.run(); }
            catch (RuntimeException failure) { fail(expectedTransfer, SubmissionActionResult.UNAVAILABLE.failureKey()); }
         });
         if (!queued) {
            fail(transferId, null);
            return SubmissionActionResult.STORAGE_FAILED;
         }
         return SubmissionActionResult.ACCEPTED;
      } catch (RuntimeException failure) {
         if (transferId != null) fail(transferId, null);
         return SubmissionActionResult.STORAGE_FAILED;
      }
   }

   private OperationSubmissionOrigin origin() {
      boolean localOnly = !session.hasDraft() && session.workspace().parts().stream()
         .allMatch(part -> part.source() == ClientSelectionPart.Source.CLIPBOARD);
      return localOnly ? OperationSubmissionOrigin.LOCAL_ONLY : session.clientOwned()
         ? OperationSubmissionOrigin.CLIENT_SELECTION : OperationSubmissionOrigin.SERVER_SELECTION;
   }

   private void fail(UUID transferId, String message) {
      // Preparation may throw before the tracker starts; only that request is released.
      if (tracker.pending() && !tracker.accepts(transferId)) return;
      cancel();
      environment.abort(transferId);
      effects.changed().run();
      if (message != null) effects.message().accept(message);
   }

   public void cancel() {
      leave();
      effects.remainder().accept(null);
   }

   /** Invalidates online work without discarding the suspended scope's durable remainder. */
   public void leave() {
      tracker.clear();
      scope = new SubmissionScope.WholeWorkspace();
      session.workspace().setLocked(false);
   }

   public void applyResult(OperationWorkspaceResultPayload payload) {
      if (payload == null || !tracker.accepts(payload.transferId(), session.interactionOwnerId())) return;
      SubmissionScope submitted = scope;
      tracker.clear();
      session.workspace().setLocked(false);
      if (payload.accepted()) {
         effects.completeSources().run();
         var applied = storage.get().settleAppliedSubmission(payload.transferId());
         effects.message().accept(applied.confirmed() ? "fastformer.message.operation_submit_success"
            : "fastformer.message.operation_submit_cleanup_pending");
         finish(submitted);
      } else if (!payload.retryable() && submitted instanceof SubmissionScope.WholeWorkspace) {
         storage.get().updateSubmissionReceipt(payload.transferId(), OperationSubmissionOutcome.FAILED_NONRETRYABLE, System.currentTimeMillis());
         boolean cleared = storage.get().clearDraftCopies(payload.transferId()).confirmed();
         effects.clearWorkspace().run();
         effects.message().accept(cleared ? "fastformer.message.operation_submit_discarded"
            : "fastformer.message.operation_submit_cleanup_pending");
      } else {
         effects.remainder().accept(null);
         scope = new SubmissionScope.WholeWorkspace();
         storage.get().updateSubmissionReceipt(payload.transferId(), OperationSubmissionOutcome.FAILED_RETRYABLE, System.currentTimeMillis());
         effects.changed().run();
         if (!payload.failedPartIds().isEmpty()) {
            session.workspace().selectOnly(payload.failedPartIds().getFirst());
            payload.failedPartIds().stream().skip(1).forEach(session.workspace()::toggleSelected);
         }
         effects.conflicts().accept(payload.failedTargetPositions());
         effects.message().accept(payload.failure().messageKey());
      }
      environment.acknowledge(payload.transferId());
   }

   /** Completes a local no-write deletion with the same remainder rules as a server result. */
   public void finish(SubmissionScope submitted) {
      var remainder = submitted.remainder(session);
      var draft = session.draftState();
      cancel();
      effects.clearWorkspace().run();
      if (remainder != null) {
         session.workspace().restoreDraftState(remainder.workspace());
      }
      if (submitted instanceof SubmissionScope.SelectionParts parts
         && parts.draft() == SubmissionScope.DraftDisposition.KEEP) session.restoreDraftState(draft);
      if (!session.workspace().isEmpty() || session.hasDraft()) session.retain();
      if (submitted instanceof SubmissionScope.SelectionParts) effects.clearSelectionPreview().run();
      effects.changed().run();
   }

   public void observeActivity(SessionActivity activity) { if (pending()) tracker.observeActivity(activity); }

   public void tick() {
      if (!pending() || !tracker.tick()) return;
      UUID expired = tracker.transferId();
      cancel();
      environment.expire(expired);
      effects.changed().run();
      effects.message().accept("fastformer.message.operation_submit_timeout");
   }
}
