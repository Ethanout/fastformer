package io.github.fastformer.client.operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Client-owned edit state. World writes are deliberately outside this type. */
public final class ClientOperationWorkspace {
   public static final int MAX_PARTS = 10;

   private final LinkedHashMap<Integer, ClientSelectionPart> parts = new LinkedHashMap<>();
   private final LinkedHashSet<Integer> selectedIds = new LinkedHashSet<>();
   private final Deque<Snapshot> undo = new ArrayDeque<>();
   private int activeId;
   private Snapshot editBaseline;

   public boolean addParts(Collection<ClientSelectionPart> additions) {
      if (additions == null || additions.isEmpty() || additions.size() > MAX_PARTS - this.parts.size()) {
         return false;
      }
      Snapshot before = this.snapshot();
      List<Integer> slots = this.freeSlots(additions.size());
      if (slots.size() != additions.size()) {
         return false;
      }
      this.selectedIds.clear();
      int index = 0;
      for (ClientSelectionPart addition : additions) {
         int slot = slots.get(index++);
         this.parts.put(slot, addition.withId(slot));
         this.selectedIds.add(slot);
         this.activeId = slot;
      }
      this.record(before);
      return true;
   }

   public boolean removeSelectedParts() {
      if (this.selectedIds.isEmpty()) {
         return false;
      }
      Snapshot before = this.snapshot();
      this.selectedIds.forEach(this.parts::remove);
      this.selectedIds.clear();
      this.activeId = 0;
      this.record(before);
      return true;
   }

   public void selectOnly(int id) {
      if (!this.parts.containsKey(id)) {
         return;
      }
      this.selectedIds.clear();
      this.selectedIds.add(id);
      this.activeId = id;
   }

   public void toggleSelected(int id) {
      if (!this.parts.containsKey(id)) {
         return;
      }
      if (!this.selectedIds.remove(id)) {
         this.selectedIds.add(id);
         this.activeId = id;
      } else if (this.activeId == id) {
         this.activeId = this.selectedIds.stream().reduce((first, second) -> second).orElse(0);
      }
   }

   public void selectAll() {
      this.selectedIds.clear();
      this.selectedIds.addAll(new TreeSet<>(this.parts.keySet()));
      if (!this.selectedIds.isEmpty() && !this.selectedIds.contains(this.activeId)) {
         this.activeId = this.selectedIds.iterator().next();
      }
   }

   public void activate(int id) {
      if (this.parts.containsKey(id) && this.selectedIds.contains(id)) {
         this.activeId = id;
      }
   }

   public boolean beginEdit() {
      if (this.editBaseline != null) {
         return false;
      }
      this.editBaseline = this.snapshot();
      return true;
   }

   public void updatePart(ClientSelectionPart part) {
      if (part != null && this.parts.containsKey(part.id())) {
         this.parts.put(part.id(), part);
      }
   }

   public void removePartDuringEdit(int id) {
      this.parts.remove(id);
      this.selectedIds.remove(id);
      if (this.activeId == id) {
         this.activeId = this.selectedIds.stream().reduce((first, second) -> second).orElse(0);
      }
   }

   public boolean finishEdit() {
      if (this.editBaseline == null) {
         return false;
      }
      Snapshot baseline = this.editBaseline;
      this.editBaseline = null;
      if (baseline.equals(this.snapshot())) {
         return false;
      }
      this.record(baseline);
      return true;
   }

   public void cancelEdit() {
      if (this.editBaseline != null) {
         this.restore(this.editBaseline);
         this.editBaseline = null;
      }
   }

   public boolean undo() {
      Snapshot previous = this.undo.pollLast();
      if (previous == null) {
         return false;
      }
      this.restore(previous);
      this.editBaseline = null;
      return true;
   }

   public void clearHistory() {
      this.undo.clear();
      this.editBaseline = null;
   }

   public int undoSize() {
      return this.undo.size();
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
      this.parts.clear();
      this.selectedIds.clear();
      this.undo.clear();
      this.activeId = 0;
      this.editBaseline = null;
   }

   public Set<Integer> partIds() {
      return Set.copyOf(new TreeSet<>(this.parts.keySet()));
   }

   public Optional<ClientSelectionPart> part(int id) {
      return Optional.ofNullable(this.parts.get(id));
   }

   public List<ClientSelectionPart> parts() {
      return this.parts.entrySet().stream()
         .sorted(Map.Entry.comparingByKey())
         .map(Map.Entry::getValue)
         .toList();
   }

   public Set<Integer> selectedIds() {
      return Set.copyOf(this.selectedIds);
   }

   public int activeId() {
      return this.activeId;
   }

   private List<Integer> freeSlots(int count) {
      List<Integer> result = new ArrayList<>(count);
      for (int id = 1; id <= MAX_PARTS && result.size() < count; id++) {
         if (!this.parts.containsKey(id)) {
            result.add(id);
         }
      }
      return result;
   }

   private void record(Snapshot before) {
      this.undo.addLast(before);
   }

   private Snapshot snapshot() {
      return new Snapshot(Map.copyOf(this.parts), Set.copyOf(this.selectedIds), this.activeId);
   }

   private void restore(Snapshot snapshot) {
      this.parts.clear();
      snapshot.parts().entrySet().stream()
         .sorted(Map.Entry.comparingByKey())
         .forEach(entry -> this.parts.put(entry.getKey(), entry.getValue()));
      this.selectedIds.clear();
      this.selectedIds.addAll(snapshot.selectedIds());
      this.activeId = snapshot.activeId();
   }

   private record Snapshot(Map<Integer, ClientSelectionPart> parts, Set<Integer> selectedIds, int activeId) {
   }
}
