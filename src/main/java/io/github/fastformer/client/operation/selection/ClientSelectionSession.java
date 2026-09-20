package io.github.fastformer.client.operation.selection;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.InteractionHover;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.InteractionVisibility;
import io.github.fastformer.client.input.drag.SelectionGestureState;
import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.session.ClientTickMailbox;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Owns selection drafts, parts, and selection membership. The phase is a
 * read-only projection until the event-driven session lifecycle is migrated.
 */
public final class ClientSelectionSession {
   private final ClientOperationWorkspace workspace = new ClientOperationWorkspace();
   private UUID interactionOwnerId = UUID.randomUUID();
   private final InteractionHover hover = new InteractionHover();
   private final ClientTickMailbox<InteractionHover.Event> hoverEvents =
      new ClientTickMailbox<>(event -> { });
   private final SelectionGestureState gestures = new SelectionGestureState();
   private final SelectionSessionLifecycle lifecycle = new SelectionSessionLifecycle();
   private SelectionInteractionScene interactionScene = SelectionInteractionScene.empty(this.interactionOwnerId);
   private final SelectionDraftStateMachine draft = new SelectionDraftStateMachine();
   private boolean altHeld;

   public ClientSelectionSession() {
   }

   public ClientOperationWorkspace workspace() {
      return this.workspace;
   }

   public SelectionGestureState gestures() {
      return this.gestures;
   }

   public SelectionSessionLifecycle.Phase lifecyclePhase() {
      return this.lifecycle.phase();
   }

   public SelectionSessionLifecycle.Transition onLifecycleEvent(SelectionSessionLifecycle.Event event) {
      return this.lifecycle.onEvent(event);
   }

   public UUID interactionOwnerId() {
      return this.interactionOwnerId;
   }

   public SelectionInteractionScene interactionScene() {
      return this.interactionScene;
   }

   public void publishInteractionScene() {
      this.interactionScene = SelectionInteractionScene.capture(this.interactionOwnerId, this.workspace, this.interactionScene);
      if (this.workspace.locked()) enqueueHoverTransitions(this.hover.update(null));
      else {
         this.hover.reconcile(this.interactionScene);
         if (this.hover.intent() != null) updateHover(this.hover.intent());
      }
   }

   public InteractionObject.Id hoveredObject() {
      return this.hover.current();
   }

   public List<InteractionHover.Event> updateHover(int partId) {
      return updateHover(new OperationInteractionIntent.Part(partId, 0));
   }

   public OperationInteractionIntent hoveredIntent() {
      return this.hover.intent();
   }

   public int hoveredPartId() {
      return switch (this.hover.intent()) {
         case OperationInteractionIntent.Part part -> part.partId();
         case OperationInteractionIntent.Face face -> face.partId();
         case OperationInteractionIntent.Gizmo gizmo -> gizmo.common() ? 0 : gizmo.partId();
         case null, default -> 0;
      };
   }

   public List<InteractionHover.Event> updateHover(OperationInteractionIntent intent) {
      InteractionObject object = this.workspace.locked() ? null : this.interactionScene.targetObject(intent);
      int partId = switch (intent) {
         case OperationInteractionIntent.Part part -> part.partId();
         case OperationInteractionIntent.Face face -> face.partId();
         case OperationInteractionIntent.Gizmo gizmo -> gizmo.partId();
         case null, default -> 0;
      };
      if (!InteractionVisibility.isVisible(object, this.workspace.selectedIds().contains(partId))) object = null;
      List<InteractionHover.Event> transitions = this.hover.updateTarget(object, intent);
      transitions.forEach(this.hoverEvents::post);
      return transitions;
   }

   /** Delivers hover enter/leave notifications at the tick boundary. */
   public void drainHoverEvents(java.util.function.Consumer<InteractionHover.Event> consumer) {
      this.hoverEvents.drain(consumer);
   }

   private void enqueueHoverTransitions(List<InteractionHover.Event> transitions) {
      transitions.forEach(this.hoverEvents::post);
   }

   public void clearLiveInteraction() {
      this.workspace.clear();
      clearDraft();
      clearTransientInteraction();
   }

   public void clearTransientInteraction() {
      this.workspace.cancelEdit();
      this.lifecycle.exit(SelectionSessionLifecycle.Cause.ENVIRONMENT_CHANGED);
      this.gestures.clear();
      this.interactionOwnerId = UUID.randomUUID();
      enqueueHoverTransitions(this.hover.update(null));
      this.hoverEvents.invalidate();
      this.interactionScene = SelectionInteractionScene.empty(this.interactionOwnerId);
      this.altHeld = false;
      publishInteractionScene();
   }

   public OperationSelectionMode selectionMode() {
      return this.draft.snapshot().selectionMode();
   }

   public void setSelectionMode(OperationSelectionMode mode) {
      this.draft.setMode(mode);
   }

   public boolean altHeld() {
      return this.altHeld;
   }

   public void setAltHeld(boolean held) {
      this.altHeld = held;
   }

   public ClientSelectionState state() {
      return state(false);
   }

   public ClientSelectionState state(boolean serverPointing) {
      return this.altHeld
         ? ClientSelectionState.ALT_FOCUSED
         : hasDraft() || this.workspace.isEmpty() && serverPointing
            ? ClientSelectionState.POINTING
            : !this.workspace.selectedIds().isEmpty() ? ClientSelectionState.FOCUSED : ClientSelectionState.UNFOCUSED;
   }

   public boolean hasDraft() {
      return !this.draft.snapshot().points().isEmpty();
   }

   public SelectionDraftResult onDraftEvent(SelectionDraftEvent event) {
      SelectionDraftResult result = this.draft.onEvent(event);
      if (result != SelectionDraftResult.REJECTED) {
         this.lifecycle.onEvent(SelectionSessionLifecycle.Event.BEGIN);
      }
      return result;
   }

   public int draftSize() {
      return this.draft.snapshot().points().size();
   }

   public BlockPos draftFirst() {
      return hasDraft() ? this.draft.snapshot().points().getFirst() : null;
   }

   public BlockPos draftAt(int index) {
      return this.draft.snapshot().points().get(index);
   }

   public List<BlockPos> draftPoints() {
      return this.draft.snapshot().points();
   }

   public List<BlockPos> selectionDraftPoints() {
      DraftState snapshot = this.draft.snapshot();
      return snapshot.points().size() >= 2
         ? List.of(snapshot.minPoint(), snapshot.maxPoint()) : snapshot.points();
   }

   public void addDraftPoint(BlockPos point) {
      if (point == null) return;
      this.lifecycle.onEvent(SelectionSessionLifecycle.Event.BEGIN);
      this.draft.addPoint(point);
   }

   public boolean expandDraftTo(BlockPos point) {
      return this.draft.expandTo(point);
   }

   public BlockPos draftMinPoint() {
      return this.draft.snapshot().minPoint();
   }

   public BlockPos draftMaxPoint() {
      return this.draft.snapshot().maxPoint();
   }

   public BlockPos removeLastDraftPoint() {
      return this.draft.removeLastPoint();
   }

   public void clearDraft() {
      this.draft.clear();
   }

   public int prismBaseCount() {
      return this.draft.snapshot().prismBaseCount();
   }

   public void restoreDraftBounds(BlockPos min, BlockPos max) {
      this.draft.restoreBounds(min, max);
   }

   public DraftState draftState() {
      return this.draft.snapshot();
   }

   public void restoreDraftState(DraftState draft) {
      this.draft.restore(draft);
      this.altHeld = false;
   }

   public void restoreDraftEdit(DraftState draft) {
      this.draft.restore(draft);
   }

   public record DraftState(
      OperationSelectionMode selectionMode,
      List<BlockPos> points,
      int prismBaseCount,
      BlockPos minPoint,
      BlockPos maxPoint
   ) {
      public DraftState {
         if (selectionMode == null) {
            throw new IllegalArgumentException("A selection draft requires a mode");
         }
         points = points == null ? List.of() : points.stream()
            .filter(java.util.Objects::nonNull)
            .map(BlockPos::immutable)
            .toList();
         minPoint = minPoint == null ? null : minPoint.immutable();
         maxPoint = maxPoint == null ? null : maxPoint.immutable();
         prismBaseCount = Math.max(0, prismBaseCount);
      }
   }
}
