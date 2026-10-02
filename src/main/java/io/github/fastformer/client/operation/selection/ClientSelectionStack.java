package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Creation order for completed selections and the optional unfinished top entry. */
public final class ClientSelectionStack {
   private static final int PENDING_ID = 0;
   private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
   private DraftState emptyDraft = new DraftState(OperationSelectionMode.CUBOID, List.of(), 0, null, null);

   public sealed interface Entry permits Pending, Complete { }
   public record Pending(DraftState points) implements Entry { }
   public record Complete(ClientSelectionPart part) implements Entry { }

   public Optional<Entry> peek() {
      return entries.isEmpty() ? Optional.empty() : Optional.of(entries.lastEntry().getValue());
   }

   public Optional<Entry> pop() {
      var removed = entries.pollLastEntry();
      return removed == null ? Optional.empty() : Optional.of(removed.getValue());
   }

   public DraftState draft() {
      return entries.get(PENDING_ID) instanceof Pending pending ? pending.points()
         : emptyDraft;
   }

   public void setDraft(DraftState draft) {
      if (draft.points().isEmpty()) {
         emptyDraft = draft;
         entries.remove(PENDING_ID);
      } else {
         fixSmartParts();
         if (emptyDraft.selectionMode() != draft.selectionMode()) {
            emptyDraft = new DraftState(draft.selectionMode(), List.of(), 0, null, null);
         }
         entries.putLast(PENDING_ID, new Pending(draft));
      }
   }

   public Optional<ClientSelectionPart> topPart() {
      return peek().filter(Complete.class::isInstance).map(Complete.class::cast).map(Complete::part);
   }

   public ClientSelectionPart part(int id) {
      return entries.get(id) instanceof Complete complete ? complete.part() : null;
   }

   public boolean containsPart(int id) { return part(id) != null; }

   public ClientSelectionPart putPart(ClientSelectionPart part) {
      if (part.id() <= 0) throw new IllegalArgumentException("A selection requires a positive part id");
      ClientSelectionPart previous = part(part.id());
      if (previous == null) fixSmartParts();
      Entry pending = entries.remove(PENDING_ID);
      entries.put(part.id(), new Complete(part));
      if (pending != null) entries.putLast(PENDING_ID, pending);
      return previous;
   }

   public ClientSelectionPart removePart(int id) {
      ClientSelectionPart previous = part(id);
      if (previous != null) entries.remove(id);
      return previous;
   }

   public List<ClientSelectionPart> parts() {
      return entries.values().stream().filter(Complete.class::isInstance)
         .map(Complete.class::cast).map(Complete::part).toList();
   }

   public Map<Integer, ClientSelectionPart> partMap() {
      var result = new LinkedHashMap<Integer, ClientSelectionPart>();
      parts().forEach(part -> result.put(part.id(), part));
      return result;
   }

   public Set<Integer> partIds() { return Set.copyOf(partMap().keySet()); }
   public int partCount() { return entries.size() - (entries.containsKey(PENDING_ID) ? 1 : 0); }

   public void retainParts(Set<Integer> ids) {
      entries.keySet().removeIf(id -> id != PENDING_ID && !ids.contains(id));
   }

   public void clearParts() { retainParts(Set.of()); }

   public void restoreParts(List<ClientSelectionPart> parts) {
      clearParts();
      Entry pending = entries.remove(PENDING_ID);
      parts.forEach(part -> entries.put(part.id(), new Complete(part)));
      if (pending != null) entries.putLast(PENDING_ID, pending);
   }

   private void fixSmartParts() {
      entries.replaceAll((id, entry) -> entry instanceof Complete complete && complete.part().smartEditable()
         ? new Complete(complete.part().fixed()) : entry);
   }
}
