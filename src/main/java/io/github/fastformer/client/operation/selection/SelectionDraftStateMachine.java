package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Owns atomic draft snapshots. Preview and undo readers never observe half an edit. */
final class SelectionDraftStateMachine {
   private final ClientSelectionStack stack;

   SelectionDraftStateMachine() { this(new ClientSelectionStack()); }

   SelectionDraftStateMachine(ClientSelectionStack stack) { this.stack = stack; }

   DraftState snapshot() {
      return this.stack.draft();
   }

   Phase phase() {
      if (snapshot().selectionMode() == OperationSelectionMode.PRISM) {
         return snapshot().prismBaseCount() == 0 ? Phase.PRISM_BASE : Phase.PRISM_HEIGHT;
      }
      return switch (snapshot().points().size()) {
         case 0 -> Phase.CUBOID_EMPTY;
         case 1 -> snapshot().secondPointOnly() ? Phase.CUBOID_SECOND : Phase.CUBOID_FIRST;
         default -> Phase.CUBOID_BOUNDS;
      };
   }

   SelectionDraftResult onEvent(SelectionDraftEvent event) {
      Transition transition = inspect(event);
      this.stack.setDraft(transition.next());
      return transition.result();
   }

   Transition inspect(SelectionDraftEvent event) {
      if (event == null || snapshot().selectionMode() == OperationSelectionMode.SMART
         || snapshot().selectionMode() == OperationSelectionMode.CONVEX_HULL && !event.alt()) {
         return rejected(snapshot());
      }
      return phase().onEvent(snapshot(), event);
   }

   void setMode(OperationSelectionMode mode) {
      if (mode != null && mode != snapshot().selectionMode()) {
         this.stack.setDraft(fromPoints(mode, mode == OperationSelectionMode.CUBOID && snapshot().points().size() >= 2
            ? List.of(snapshot().minPoint(), snapshot().maxPoint()) : snapshot().points(), 0));
      }
   }

   void clear() {
      this.stack.setDraft(fromPoints(snapshot().selectionMode(), List.of(), 0));
   }

   void restore(DraftState snapshot) {
      if (snapshot == null) {
         clear();
         return;
      }
      DraftState restored = fromPoints(snapshot.selectionMode(), snapshot.points(), snapshot.prismBaseCount(), snapshot.secondPointOnly());
      this.stack.setDraft(withBounds(restored, snapshot.minPoint(), snapshot.maxPoint()));
   }

   void addPoint(BlockPos point) {
      if (point != null) this.stack.setDraft(append(snapshot(), point));
   }

   BlockPos removeLastPoint() {
      if (snapshot().points().isEmpty()) return null;
      BlockPos removed = snapshot().points().getLast();
      this.stack.setDraft(removeLast(snapshot(), false));
      return removed;
   }

   boolean expandTo(BlockPos point) {
      DraftState expanded = expand(snapshot(), point);
      if (expanded == snapshot()) return false;
      this.stack.setDraft(expanded);
      return true;
   }

   void restoreBounds(BlockPos min, BlockPos max) {
      this.stack.setDraft(withBounds(snapshot(), min, max));
   }

   private static DraftState withBounds(DraftState draft, BlockPos min, BlockPos max) {
      if (min == null || max == null || draft.points().size() < 2) return draft;
      return new DraftState(draft.selectionMode(), draft.points(), draft.prismBaseCount(), min(min, max), max(min, max));
   }

   private static DraftState append(DraftState draft, BlockPos point) {
      var points = new ArrayList<>(draft.points());
      points.add(point);
      return fromPoints(draft.selectionMode(), points, draft.prismBaseCount());
   }

   private static DraftState removeLast(DraftState draft, boolean reopenBase) {
      var points = draft.points().subList(0, draft.points().size() - 1);
      int baseCount = reopenBase && points.size() < draft.prismBaseCount() ? 0 : draft.prismBaseCount();
      return fromPoints(draft.selectionMode(), points, baseCount);
   }

   private static DraftState expand(DraftState draft, BlockPos point) {
      if (point == null || draft.points().size() < 2) return draft;
      BlockPos min = min(draft.minPoint(), point);
      BlockPos max = max(draft.maxPoint(), point);
      return min.equals(draft.minPoint()) && max.equals(draft.maxPoint()) ? draft
         : new DraftState(draft.selectionMode(), draft.points(), draft.prismBaseCount(), min, max);
   }

   private static DraftState fromPoints(OperationSelectionMode mode, List<BlockPos> points, int baseCount) {
      return fromPoints(mode, points, baseCount, false);
   }

   private static DraftState fromPoints(OperationSelectionMode mode, List<BlockPos> points, int baseCount, boolean secondOnly) {
      var fixed = points == null ? List.<BlockPos>of() : points.stream()
         .filter(java.util.Objects::nonNull).map(BlockPos::immutable).toList();
      BlockPos min = null;
      BlockPos max = null;
      for (BlockPos point : fixed) {
         min = min == null ? point : min(min, point);
         max = max == null ? point : max(max, point);
      }
      return new DraftState(mode, fixed, baseCount, min, max, secondOnly);
   }

   private static BlockPos min(BlockPos a, BlockPos b) {
      return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
   }

   private static BlockPos max(BlockPos a, BlockPos b) {
      return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
   }

   private static Transition rejected(DraftState draft) {
      return new Transition(draft, SelectionDraftResult.REJECTED);
   }

   record Transition(DraftState next, SelectionDraftResult result) { }

   enum Phase {
      CUBOID_EMPTY, CUBOID_FIRST, CUBOID_SECOND, CUBOID_BOUNDS, PRISM_BASE, PRISM_HEIGHT;

      Transition onEvent(DraftState draft, SelectionDraftEvent event) {
         return switch (this) {
            case CUBOID_EMPTY, CUBOID_FIRST, CUBOID_SECOND, CUBOID_BOUNDS -> cuboid(draft, event);
            case PRISM_BASE, PRISM_HEIGHT -> prism(draft, event);
         };
      }

      private Transition cuboid(DraftState draft, SelectionDraftEvent event) {
         if (this == CUBOID_BOUNDS && event.button() == SelectionDraftEvent.Button.MIDDLE) {
            DraftState next = expand(draft, event.point());
            return next == draft ? rejected(draft) : new Transition(next, SelectionDraftResult.READY);
         }
         BlockPos first = draft.firstPoint();
         BlockPos second = draft.secondPoint();
         switch (event.button()) {
            case LEFT -> first = event.point();
            case RIGHT -> second = event.point();
            case MIDDLE -> { if (first == null) first = event.point(); else second = event.point(); }
         }
         boolean complete = first != null && second != null;
         var points = complete ? List.of(first, second) : List.of(first == null ? second : first);
         var next = fromPoints(draft.selectionMode(), points, 0,
            first == null && draft.selectionMode() == OperationSelectionMode.CUBOID);
         return complete && next.equals(draft) ? rejected(draft)
            : new Transition(next, complete ? SelectionDraftResult.READY : SelectionDraftResult.UPDATED);
      }

      private Transition prism(DraftState draft, SelectionDraftEvent event) {
         if (event.button() == SelectionDraftEvent.Button.LEFT) {
            return draft.points().isEmpty() ? new Transition(append(draft, event.point()), SelectionDraftResult.UPDATED)
               : new Transition(removeLast(draft, true), SelectionDraftResult.UPDATED);
         }
         if (this == PRISM_BASE && event.button() == SelectionDraftEvent.Button.RIGHT
            && draft.points().size() >= 3 && event.point().equals(draft.points().getFirst())) {
            return new Transition(new DraftState(draft.selectionMode(), draft.points(), draft.points().size(),
               draft.minPoint(), draft.maxPoint()), SelectionDraftResult.UPDATED);
         }
         DraftState next = append(draft, event.point());
         return new Transition(next, this == PRISM_HEIGHT && next.points().size() > next.prismBaseCount()
            ? SelectionDraftResult.READY : SelectionDraftResult.UPDATED);
      }
   }
}
