package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.interaction.InteractionHover;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.InteractionVisibility;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.core.async.TickMailbox;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.client.operation.selection.tool.SelectionTool;
import io.github.fastformer.client.operation.selection.tool.SelectionToolState;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import net.minecraft.resources.ResourceLocation;
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
   private final TickMailbox<InteractionHover.Event> hoverEvents =
      new TickMailbox<>(event -> { });
   private final SelectionSessionLifecycle lifecycle = new SelectionSessionLifecycle();
   private SelectionInteractionScene interactionScene = SelectionInteractionScene.empty(this.interactionOwnerId);
   private final SelectionDraftStateMachine draft = new SelectionDraftStateMachine(this.workspace.selections());
   private boolean altHeld;
   private boolean retainAfterCancel;
   private boolean clientOwned;

   public boolean retained() { return retainAfterCancel; }
   public void retain() { retainAfterCancel = true; clientOwned = true; }
   public boolean clientOwned() { return clientOwned; }

   public ClientSelectionSession() {
   }

   public ClientOperationWorkspace workspace() {
      return this.workspace;
   }

   public ClientSelectionStack stack() { return this.workspace.selections(); }
   /** World history changes blocks without reopening or replacing a selection session. */
   public void onWorldHistory(io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind kind) {
      if (kind == io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.RECORD) workspace.clearHistory();
   }

   public void closeUndoably() {
      var before = draftState();
      workspace.closeUndoably(() -> { restoreDraftState(before); retain(); }, before.points().size());
      clearDraft();
      clearTransientInteraction();
      this.draft.setTool(SelectionToolPreference.id());
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
      this.clientOwned = false;
      this.workspace.clear();
      clearDraft();
      clearTransientInteraction();
      this.draft.setTool(SelectionToolPreference.id());
   }

   public void clearTransientInteraction() {
      this.workspace.cancelEdit();
      this.lifecycle.exit(SelectionSessionLifecycle.Cause.ENVIRONMENT_CHANGED);
      this.interactionOwnerId = UUID.randomUUID();
      enqueueHoverTransitions(this.hover.update(null));
      this.hoverEvents.invalidate();
      this.interactionScene = SelectionInteractionScene.empty(this.interactionOwnerId);
      this.altHeld = false;
      this.retainAfterCancel = false;
      publishInteractionScene();
   }

   public OperationSelectionMode selectionMode() {
      return this.draft.snapshot().selectionMode();
   }

   public void setSelectionMode(OperationSelectionMode mode) {
      this.draft.setMode(mode);
   }

   public ResourceLocation selectionToolId() { return draft.snapshot().toolId(); }
   public SelectionTool selectionTool() { return SelectionTools.require(selectionToolId()); }
   public void setSelectionTool(ResourceLocation id) { draft.setTool(id); }
   public boolean selectionReady() { return selectionTool().ready(draft.snapshot().toolState()); }

   public void startNewSession() {
      if (workspace.isEmpty() && !hasDraft()) this.draft.setTool(SelectionToolPreference.id());
      retain();
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
         this.clientOwned = true;
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
      return selectionTool().undoPreviousPoint() && snapshot.points().size() >= 2
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

   public boolean undoCuboidPoint() {
      if (!selectionTool().undoPreviousPoint() || workspace.locked() || workspace.editing()) return false;
      if (hasDraft()) {
         removeLastDraftPoint();
         return true;
      }
      var part = stack().topPart().filter(value -> value.isOriginalSelection()
         && value.selection() != null && value.selection().mode() == OperationSelectionMode.CUBOID).orElse(null);
      if (part == null || part.selection().point1() == null) return false;
      workspace.restorePartStatesWithoutHistory(workspace.parts().stream().filter(value -> value.id() != part.id()).toList());
      BlockPos first = part.selection().point1();
      restoreDraftState(new DraftState(OperationSelectionMode.CUBOID, List.of(first), 0, first, first));
      return true;
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
      this.retainAfterCancel = false;
   }

   public void restoreDraftEdit(DraftState draft) {
      this.draft.restore(draft);
   }

   public record DraftState(SelectionToolState toolState) {
      public DraftState(OperationSelectionMode selectionMode, List<BlockPos> points, int prismBaseCount,
         BlockPos minPoint, BlockPos maxPoint) {
         this(selectionMode, points, prismBaseCount, minPoint, maxPoint, false);
      }

      public DraftState(OperationSelectionMode selectionMode, List<BlockPos> points, int prismBaseCount,
         BlockPos minPoint, BlockPos maxPoint, boolean secondPointOnly) {
         this(new SelectionToolState(SelectionTools.builtinId(selectionMode), selectionMode, points, prismBaseCount,
            minPoint, maxPoint, secondPointOnly, java.util.Map.of()));
      }
      public DraftState { java.util.Objects.requireNonNull(toolState, "toolState"); }
      public ResourceLocation toolId() { return toolState.toolId(); }
      public OperationSelectionMode selectionMode() { return toolState.geometry(); }
      public List<BlockPos> points() { return toolState.points(); }
      public int prismBaseCount() { return toolState.prismBaseCount(); }
      public BlockPos minPoint() { return toolState.minPoint(); }
      public BlockPos maxPoint() { return toolState.maxPoint(); }
      public boolean secondPointOnly() { return toolState.secondPointOnly(); }
      public BlockPos firstPoint() { return toolState.firstPoint(); }
      public BlockPos secondPoint() { return toolState.secondPoint(); }
   }
}
