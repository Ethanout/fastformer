package io.github.fastformer.client.operation.selection;

import io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Owns atomic draft snapshots. Preview and undo readers never observe half an edit. */
final class SelectionDraftStateMachine {
   private DraftState current = fromPoints(OperationSelectionMode.CUBOID, List.of(), 0);

   DraftState snapshot() {
      return this.current;
   }

   Phase phase() {
      if (this.current.selectionMode() == OperationSelectionMode.PRISM) {
         return this.current.prismBaseCount() == 0 ? Phase.PRISM_BASE : Phase.PRISM_HEIGHT;
      }
      return switch (this.current.points().size()) {
         case 0 -> Phase.CUBOID_EMPTY;
         case 1 -> Phase.CUBOID_FIRST;
         default -> Phase.CUBOID_BOUNDS;
      };
   }

   SelectionDraftResult onEvent(SelectionDraftEvent event) {
      Transition transition = inspect(event);
      this.current = transition.next();
      return transition.result();
   }

   Transition inspect(SelectionDraftEvent event) {
      if (event == null || this.current.selectionMode() == OperationSelectionMode.CONVEX_HULL && !event.alt()) {
         return rejected(this.current);
      }
      return phase().onEvent(this.current, event);
   }

   void setMode(OperationSelectionMode mode) {
      if (mode != null && mode != this.current.selectionMode()) {
         this.current = new DraftState(mode, this.current.points(), this.current.prismBaseCount(),
            this.current.minPoint(), this.current.maxPoint());
      }
   }

   void clear() {
      this.current = fromPoints(this.current.selectionMode(), List.of(), 0);
   }

   void restore(DraftState snapshot) {
      if (snapshot == null) {
         clear();
         return;
      }
      DraftState restored = fromPoints(snapshot.selectionMode(), snapshot.points(), snapshot.prismBaseCount());
      this.current = withBounds(restored, snapshot.minPoint(), snapshot.maxPoint());
   }

   void addPoint(BlockPos point) {
      if (point != null) this.current = append(this.current, point);
   }

   BlockPos removeLastPoint() {
      if (this.current.points().isEmpty()) return null;
      BlockPos removed = this.current.points().getLast();
      this.current = removeLast(this.current, false);
      return removed;
   }

   boolean expandTo(BlockPos point) {
      DraftState expanded = expand(this.current, point);
      if (expanded == this.current) return false;
      this.current = expanded;
      return true;
   }

   void restoreBounds(BlockPos min, BlockPos max) {
      this.current = withBounds(this.current, min, max);
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
      var fixed = points == null ? List.<BlockPos>of() : points.stream()
         .filter(java.util.Objects::nonNull).map(BlockPos::immutable).toList();
      BlockPos min = null;
      BlockPos max = null;
      for (BlockPos point : fixed) {
         min = min == null ? point : min(min, point);
         max = max == null ? point : max(max, point);
      }
      return new DraftState(mode, fixed, baseCount, min, max);
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
      CUBOID_EMPTY, CUBOID_FIRST, CUBOID_BOUNDS, PRISM_BASE, PRISM_HEIGHT;

      Transition onEvent(DraftState draft, SelectionDraftEvent event) {
         return switch (this) {
            case CUBOID_EMPTY, CUBOID_FIRST, CUBOID_BOUNDS -> cuboid(draft, event);
            case PRISM_BASE, PRISM_HEIGHT -> prism(draft, event);
         };
      }

      private Transition cuboid(DraftState draft, SelectionDraftEvent event) {
         if (event.button() == SelectionDraftEvent.Button.LEFT) {
            return new Transition(fromPoints(draft.selectionMode(), List.of(event.point()), 0), SelectionDraftResult.UPDATED);
         }
         if (event.alt() && event.button() == SelectionDraftEvent.Button.MIDDLE) {
            DraftState next = this == CUBOID_BOUNDS ? expand(draft, event.point()) : append(draft, event.point());
            return next == draft ? rejected(draft) : new Transition(next, SelectionDraftResult.UPDATED);
         }
         return switch (this) {
            case CUBOID_EMPTY -> event.alt()
               ? new Transition(append(draft, event.point()), SelectionDraftResult.UPDATED) : rejected(draft);
            case CUBOID_FIRST -> new Transition(append(draft, event.point()), SelectionDraftResult.READY);
            case CUBOID_BOUNDS -> {
               if (!event.alt() || event.button() != SelectionDraftEvent.Button.RIGHT) yield rejected(draft);
               var points = new ArrayList<>(draft.points());
               points.set(1, event.point());
               yield new Transition(fromPoints(draft.selectionMode(), points, draft.prismBaseCount()), SelectionDraftResult.READY);
            }
            default -> throw new IllegalStateException("Cuboid event requires a cuboid phase");
         };
      }

      private Transition prism(DraftState draft, SelectionDraftEvent event) {
         if (event.button() == SelectionDraftEvent.Button.LEFT) {
            return draft.points().isEmpty() ? rejected(draft)
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
