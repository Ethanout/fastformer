package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

/** Client-owned edit state. World writes are deliberately outside this type. */
public final class ClientOperationWorkspace {
   /** Codec compatibility sentinel; the client workspace itself is unbounded. */
   public static final int MAX_PARTS = Integer.MAX_VALUE;

   private final LinkedHashMap<Integer, ClientSelectionPart> parts = new LinkedHashMap<>();
   private final Map<Integer, Long> interactionIds = new LinkedHashMap<>();
   private long interactionSequence;
   private final LinkedHashSet<Integer> selectedIds = new LinkedHashSet<>();
   /**
    * Every client-side edit and input event shares this one history. The history
    * holds a count budget and a weight budget, so a long editing session cannot
    * retain an unlimited chain of workspace snapshots.
    */
   private final ClientOperationEventStack history;
   private int activeId;
   private Snapshot editBaseline;
   private long revision;
   private EditToken activeEdit;
   private boolean locked;

   public ClientOperationWorkspace() {
      this(ClientOperationEventStack.DEFAULT_RECORD_LIMIT, ClientOperationEventStack.DEFAULT_WEIGHT_LIMIT);
   }

   /**
    * Builds a workspace with explicit history budgets.
    *
    * <p>The budgets are parameters so a settings update or a test can bound the
    * local undo chain without changing the workspace behaviour.</p>
    */
   ClientOperationWorkspace(int recordLimit, int weightLimit) {
      this.history = new ClientOperationEventStack(recordLimit, weightLimit);
   }

   public boolean addParts(Collection<ClientSelectionPart> additions) {
      if (this.locked || this.editBaseline != null || additions == null || additions.isEmpty()) {
         return false;
      }
      Snapshot before = this.snapshot();
      if (!this.addPartsWithoutHistory(additions)) {
         return false;
      }
      this.record(before);
      return true;
   }

   /** Adds parts for a surrounding composite client event without its own history node. */
   public boolean addPartsWithoutHistory(Collection<ClientSelectionPart> additions) {
      if (this.locked || this.editBaseline != null || additions == null || additions.isEmpty()) {
         return false;
      }
      this.selectedIds.clear();
      for (ClientSelectionPart addition : additions) {
         int slot = this.allocateId();
         this.interactionSequence = Math.incrementExact(this.interactionSequence);
         this.parts.put(slot, addition.withId(slot));
         this.interactionIds.put(slot, this.interactionSequence);
         this.selectedIds.add(slot);
         this.activeId = slot;
      }
      this.changed();
      return true;
   }

   public boolean removeSelectedParts() {
      if (this.locked || this.editBaseline != null || this.selectedIds.isEmpty()) {
         return false;
      }
      Snapshot before = this.snapshot();
      this.selectedIds.forEach(this.parts::remove);
      this.selectedIds.forEach(this.interactionIds::remove);
      this.selectedIds.clear();
      this.activeId = 0;
      this.record(before);
      this.changed();
      return true;
   }

   public void selectOnly(int id) {
      if (this.locked || this.editBaseline != null || !this.parts.containsKey(id)) {
         return;
      }
      if (this.selectedIds.size() == 1 && this.selectedIds.contains(id) && this.activeId == id) {
         return;
      }
      Snapshot before = this.snapshot();
      this.selectedIds.clear();
      this.selectedIds.add(id);
      this.activeId = id;
      this.record(before);
      this.changed();
   }

   public void toggleSelected(int id) {
      if (this.locked || this.editBaseline != null || !this.parts.containsKey(id)) {
         return;
      }
      Snapshot before = this.snapshot();
      if (!this.selectedIds.remove(id)) {
         this.selectedIds.add(id);
         this.activeId = id;
      } else if (this.activeId == id) {
         this.activeId = this.selectedIds.stream().reduce((first, second) -> second).orElse(0);
      }
      this.record(before);
      this.changed();
   }

   public void selectAll() {
      if (this.locked || this.editBaseline != null) {
         return;
      }
      Snapshot snapshot = this.snapshot();
      Set<Integer> before = Set.copyOf(this.selectedIds);
      int beforeActive = this.activeId;
      this.selectedIds.clear();
      this.selectedIds.addAll(new TreeSet<>(this.parts.keySet()));
      if (!this.selectedIds.isEmpty() && !this.selectedIds.contains(this.activeId)) {
         this.activeId = this.selectedIds.iterator().next();
      }
      if (!before.equals(this.selectedIds) || beforeActive != this.activeId) {
         this.record(snapshot);
         this.changed();
      }
   }

   public void activate(int id) {
      if (!this.locked && this.editBaseline == null
         && this.parts.containsKey(id) && this.selectedIds.contains(id)) {
         if (this.activeId == id) {
            return;
         }
         this.activeId = id;
         this.changed();
      }
   }

   public boolean beginEdit() {
      if (this.locked || this.editBaseline != null) {
         return false;
      }
      this.editBaseline = this.snapshot();
      this.activeEdit = new EditToken();
      return true;
   }

   public EditToken activeEditToken() {
      return this.activeEdit;
   }

   public boolean ownsEdit(EditToken token) {
      return token != null && token == this.activeEdit && this.editBaseline != null;
   }

   public void updatePart(ClientSelectionPart part) {
      if (!this.locked && this.editBaseline != null && part != null && this.parts.containsKey(part.id())) {
         ClientSelectionPart previous = this.parts.put(part.id(), part);
         if (!part.equals(previous)) {
            this.changed();
         }
      }
   }

   public void removePartDuringEdit(int id) {
      if (this.locked || this.editBaseline == null) {
         return;
      }
      if (this.parts.remove(id) == null) {
         return;
      }
      this.interactionIds.remove(id);
      this.selectedIds.remove(id);
      if (this.activeId == id) {
         this.activeId = this.selectedIds.stream().reduce((first, second) -> second).orElse(0);
      }
      this.changed();
   }

   public boolean finishEdit() {
      if (this.editBaseline == null) {
         return false;
      }
      Snapshot baseline = this.editBaseline;
      this.editBaseline = null;
      this.activeEdit = null;
      if (baseline.equals(this.snapshot())) {
         return false;
      }
      this.record(baseline);
      return true;
   }

   public boolean finishEdit(EditToken token) {
      return this.ownsEdit(token) && this.finishEdit();
   }

   public void cancelEdit() {
      if (this.editBaseline != null) {
         this.restore(this.editBaseline);
         this.editBaseline = null;
         this.activeEdit = null;
         this.changed();
      }
   }

   public boolean cancelEdit(EditToken token) {
      if (!this.ownsEdit(token)) {
         return false;
      }
      this.cancelEdit();
      return true;
   }

   public boolean undo() {
      if (this.locked || this.editBaseline != null) {
         return false;
      }
      if (this.history.undo()) {
         this.changed();
         return true;
      }
      return false;
   }

   public void clearHistory() {
      this.history.clear();
      this.editBaseline = null;
      this.activeEdit = null;
   }

   public int undoSize() {
      return this.history.size();
   }

   /** Adds a reversible client input event to the same undo entry point. */
   public void pushEvent(Runnable inverse) {
      this.pushEvent(inverse, ClientOperationEventStack.Retention.UNMEASURED);
   }

   /**
    * Adds a reversible client input event with a declared retention.
    *
    * <p>The caller declares the references that its inverse keeps alive. A
    * closure that hides a large payload must declare it, so the weight budget can
    * account for it.</p>
    */
   public void pushEvent(Runnable inverse, ClientOperationEventStack.Retention retention) {
      if (!this.locked && this.editBaseline == null && inverse != null) {
         this.history.push(inverse, retention);
         this.changed();
      }
   }

   public int size() {
      return this.parts.size();
   }

   public boolean isEmpty() {
      return this.parts.isEmpty();
   }

   public List<ClientSelectionPart> selectedParts() {
      return this.parts().stream().filter(part -> this.selectedIds.contains(part.id())).toList();
   }

   public void clear() {
      boolean changed = !this.parts.isEmpty() || !this.selectedIds.isEmpty() || this.editBaseline != null;
      this.parts.clear();
      this.selectedIds.clear();
      this.interactionIds.clear();
      this.history.clear();
      this.activeId = 0;
      this.editBaseline = null;
      this.activeEdit = null;
      this.locked = false;
      if (changed) {
         this.changed();
      }
   }

   public long revision() {
      return this.revision;
   }

   public boolean editing() {
      return this.editBaseline != null;
   }

   public boolean locked() {
      return this.locked;
   }

   public void setLocked(boolean value) {
      if (value && this.editBaseline != null) {
         throw new IllegalStateException("Cannot lock a workspace during an edit");
      }
      if (this.locked != value) {
         this.locked = value;
         this.changed();
      }
   }

   public Set<Integer> partIds() {
      return Set.copyOf(new TreeSet<>(this.parts.keySet()));
   }

   public Optional<ClientSelectionPart> part(int id) {
      return Optional.ofNullable(this.parts.get(id));
   }

   /** Transient identity survives edits, but a reused display number gets a new identity. */
   public long interactionId(int id) {
      Long identity = this.interactionIds.get(id);
      if (identity == null) {
         throw new IllegalArgumentException("Unknown workspace part: " + id);
      }
      return identity;
   }

   public List<ClientSelectionPart> parts() {
      return this.parts.entrySet().stream()
         .sorted(Map.Entry.comparingByKey())
         .map(Map.Entry::getValue)
         .toList();
   }

   /** Captures only durable draft data. Gesture, lock, and history state stay transient. */
   public DraftState draftState() {
      return new DraftState(this.parts(), this.selectedIds(), this.activeId);
   }

   /** Replaces the live draft without restoring a gesture, lock, or undo history. */
   public void restoreDraftState(DraftState draft) {
      if (draft == null) {
         this.clear();
         return;
      }
      LinkedHashMap<Integer, ClientSelectionPart> restored = new LinkedHashMap<>();
      for (ClientSelectionPart part : draft.parts()) {
         if (part.id() <= 0 || restored.putIfAbsent(part.id(), part) != null) {
            throw new IllegalArgumentException("A workspace draft requires unique positive part ids");
         }
      }
      this.clear();
      this.parts.putAll(restored);
      for (int id : restored.keySet()) {
         this.interactionSequence = Math.incrementExact(this.interactionSequence);
         this.interactionIds.put(id, this.interactionSequence);
      }
      draft.selectedIds().stream().filter(this.parts::containsKey).forEach(this.selectedIds::add);
      this.activeId = this.selectedIds.contains(draft.activeId()) ? draft.activeId() : 0;
      this.locked = false;
      this.editBaseline = null;
      this.activeEdit = null;
      this.history.clear();
      this.changed();
   }

   public Set<Integer> selectedIds() {
      return Set.copyOf(this.selectedIds);
   }

   /** Clears selection as one undoable workspace event, preserving all parts. */
   public boolean clearSelectionForNewDraft() {
      if (this.locked || this.editBaseline != null || this.selectedIds.isEmpty()) {
         return false;
      }
      Snapshot before = this.snapshot();
      boolean changed = this.clearSelectionForNewDraftWithoutHistory();
      if (changed) {
         this.record(before);
         this.changed();
      }
      return changed;
   }

   /** Clears selection for a surrounding composite event without its own history node. */
   public boolean clearSelectionForNewDraftWithoutHistory() {
      if (this.locked || this.editBaseline != null || this.selectedIds.isEmpty()) {
         return false;
      }
      this.selectedIds.clear();
      this.activeId = 0;
      return true;
   }

   public SelectionState selectionState() {
      return new SelectionState(Set.copyOf(this.selectedIds), this.activeId);
   }

   public void restoreSelectionState(SelectionState state) {
      if (state == null || this.locked || this.editBaseline != null) return;
      if (this.restoreSelectionStateWithoutHistory(state)) {
         this.changed();
      }
   }

   /** Restores selection without notifying or recording; for composite history inverses. */
   public boolean restoreSelectionStateWithoutHistory(SelectionState state) {
      if (state == null || this.locked || this.editBaseline != null) return false;
      this.selectedIds.clear();
      state.ids().stream().filter(this.parts::containsKey).forEach(this.selectedIds::add);
      this.activeId = this.selectedIds.contains(state.activeId()) ? state.activeId() : 0;
      return true;
   }

   /** Restores the part set without creating another history entry. */
   public void restoreParts(Set<Integer> keepIds) {
      if (this.locked || this.editBaseline != null || keepIds == null) return;
      this.parts.keySet().removeIf(id -> !keepIds.contains(id));
      this.interactionIds.keySet().retainAll(this.parts.keySet());
      this.selectedIds.removeIf(id -> !this.parts.containsKey(id));
      if (!this.selectedIds.contains(this.activeId)) {
         this.activeId = this.selectedIds.stream().reduce((first, second) -> second).orElse(0);
      }
   }

   public int activeId() {
      return this.activeId;
   }

   private int allocateId() {
      int candidate = 1;
      while (this.parts.containsKey(candidate)) {
         if (candidate == Integer.MAX_VALUE) {
            throw new IllegalStateException("Workspace part id space exhausted");
         }
         candidate++;
      }
      return candidate;
   }

   private void record(Snapshot before) {
      if (this.sameState(before)) {
         // An edit that changed nothing must not take an undo slot, and it must
         // not retain a snapshot either.
         return;
      }
      this.history.push(() -> this.restore(before), before.retention());
   }

   /**
    * Reports whether the live state still matches this snapshot, by reference.
    *
    * <p>A part is immutable, so any real content change replaces the instance.
    * This check therefore never misses a change, and it never walks the retained
    * block maps.</p>
    */
   private boolean sameState(Snapshot before) {
      if (before.activeId() != this.activeId
         || before.parts().size() != this.parts.size()
         || !before.selectedIds().equals(this.selectedIds)) {
         return false;
      }
      for (Map.Entry<Integer, ClientSelectionPart> entry : this.parts.entrySet()) {
         if (entry.getValue() != before.parts().get(entry.getKey())) {
            return false;
         }
      }
      return true;
   }

   /** Current undo weight in retained-reference units. */
   long historyWeight() {
      return this.history.retainedWeight();
   }

   /** Nodes that the history budgets dropped, oldest first. */
   long historyDiscardedRecords() {
      return this.history.discardedRecords();
   }

   private Snapshot snapshot() {
      return new Snapshot(Map.copyOf(this.parts), Map.copyOf(this.interactionIds), Set.copyOf(this.selectedIds), this.activeId);
   }

   private void restore(Snapshot snapshot) {
      this.parts.clear();
      this.interactionIds.clear();
      this.interactionIds.putAll(snapshot.interactionIds());
      snapshot.parts().entrySet().stream()
         .sorted(Map.Entry.comparingByKey())
         .forEach(entry -> this.parts.put(entry.getKey(), entry.getValue()));
      this.selectedIds.clear();
      this.selectedIds.addAll(snapshot.selectedIds());
      this.activeId = snapshot.activeId();
   }

   private void changed() {
      this.revision++;
   }

   public static final class EditToken {
      private EditToken() { }
   }

   public record SelectionState(Set<Integer> ids, int activeId) { }

   public record DraftState(List<ClientSelectionPart> parts, Set<Integer> selectedIds, int activeId) {
      public DraftState {
         parts = parts == null ? List.of() : List.copyOf(parts);
         selectedIds = selectedIds == null ? Set.of() : Set.copyOf(selectedIds);
      }
   }

   private record Snapshot(
      Map<Integer, ClientSelectionPart> parts, Map<Integer, Long> interactionIds, Set<Integer> selectedIds, int activeId
   ) {
      /** Units for the references that this snapshot owns itself. */
      int nodeUnits() {
         return 1 + this.parts.size() + this.interactionIds.size() + this.selectedIds.size();
      }

      /**
       * Payload holders that this snapshot keeps alive, by identity.
       *
       * <p>An unchanged part keeps the same block maps, so several snapshots
       * of that part share one payload and the history charges it once.</p>
       */
      List<ClientOperationEventStack.SharedPayload> sharedPayloads() {
         List<ClientOperationEventStack.SharedPayload> payloads = new ArrayList<>();
         IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
         for (ClientSelectionPart part : this.parts.values()) {
            addPayload(payloads, seen, part.blocks());
            addPayload(payloads, seen, part.sourceSnapshot());
         }
         return payloads;
      }

      ClientOperationEventStack.Retention retention() {
         return ClientOperationEventStack.Retention.of(this.nodeUnits(), this.sharedPayloads());
      }
   }

   private static void addPayload(
      List<ClientOperationEventStack.SharedPayload> payloads,
      IdentityHashMap<Object, Boolean> seen,
      Map<BlockPos, ClientBlockSnapshot> holder
   ) {
      if (holder == null || seen.put(holder, Boolean.TRUE) != null) {
         return;
      }
      payloads.add(ClientOperationEventStack.SharedPayload.of(holder, holder.size()));
   }
}
