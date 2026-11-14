package io.github.fastformer.common.selection.session;

import static io.github.fastformer.common.selection.session.OperationTransformState.addAxis;

import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionStage;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.PointMotionLimits;
import io.github.fastformer.common.selection.geometry.SelectionPrism;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Owns prism drag constraints and atomically validates point or base movement before keeping it. */
final class OperationPointEditing {
   private Drag current;

   record Context(OperationSelectionMode mode, SelectionPointState state, boolean confirmed,
      BlockPos minOffset, BlockPos maxOffset, int hullInflation) {
      boolean baseClosed() { return mode == OperationSelectionMode.PRISM && state.prismBasePointCount >= 3; }
      boolean ready() {
         if (mode == OperationSelectionMode.CUBOID) return state.first != null && state.second != null;
         if (mode == OperationSelectionMode.PRISM)
            return state.prismBasePointCount > 0 && state.points().size() > state.prismBasePointCount;
         return mode.stage(state.points().size()) == OperationSelectionStage.READY;
      }
      boolean validVolume() {
         return !ready() || OperationSelectionVolume.create(mode, state.points(), state.prismBasePointCount,
            minOffset, maxOffset, hullInflation) != null;
      }
   }

   void clear() { current = null; }

   boolean move(Context context, int index, int axis, int steps) {
      if (context.mode() == OperationSelectionMode.CUBOID || context.confirmed()
         || steps == 0 || axis < 0 || axis > 2 || index < 0) return false;
      List<BlockPos> before = context.state().points();
      if (index >= before.size()) return false;
      BlockPos target = before.get(index).offset(addAxis(BlockPos.ZERO, axis, steps));
      return moveTo(context, index, snapNearest(constraint(context, index), target), before, point -> true);
   }

   boolean drag(Context context, int index, BlockPos target, boolean finish) {
      if (!accepts(context, index, target)) { clear(); return false; }
      boolean started = begin(context, index);
      context.state().selectedPointIndex = index;
      List<BlockPos> before = context.state().points();
      boolean changed = moveTo(context, index, snapNearest(current, target), before, point -> true);
      if (finish) clear();
      return started || changed || finish;
   }

   boolean drag(Context context, int index, BlockPos target, OperationPointDragConstraint requested,
      boolean finish, Predicate<BlockPos> validator) {
      if (!accepts(context, index, target)) { clear(); return false; }
      boolean started = begin(context, index);
      context.state().selectedPointIndex = index;
      List<BlockPos> before = context.state().points();
      OperationPointDragConstraint available = available(current, requested);
      if (current.activeConstraint() != available) current = current.rebase(before.get(index), available);
      ConstrainedPoint constrained = switch (available) {
         case PLANE -> new ConstrainedPoint(current.plane().snap(target), available);
         case LINE -> new ConstrainedPoint(current.line().snap(target), available);
         case FREE -> new ConstrainedPoint(target, available);
      };
      boolean changed = moveTo(context, index, constrained, before, validator);
      if (finish) clear();
      return started || changed || finish;
   }

   private static boolean accepts(Context context, int index, BlockPos target) {
      return !context.confirmed() && context.mode() == OperationSelectionMode.PRISM
         && target != null && index >= 0 && index < context.state().points().size();
   }
   private boolean begin(Context context, int index) {
      if (current != null && current.pointIndex() == index) return false;
      current = constraint(context, index);
      return true;
   }

   private static boolean moveTo(Context context, int index, ConstrainedPoint constrained,
      List<BlockPos> before, Predicate<BlockPos> validator) {
      BlockPos target = constrained.target(), previous = before.get(index);
      if (previous.equals(target) || !PointMotionLimits.accepts(previous, target) || !validator.test(target)) return false;
      SelectionPointState state = context.state();
      int baseCount = context.baseClosed() ? state.prismBasePointCount : before.size();
      boolean moveBase = index == 0 && baseCount >= 3 && constrained.constraint() != OperationPointDragConstraint.FREE;
      if (moveBase) {
         BlockPos offset = target.subtract(previous);
         int movedCount = constrained.constraint() == OperationPointDragConstraint.PLANE ? before.size() : baseCount;
         for (int point = 0; point < movedCount; point++) if (!validator.test(before.get(point).offset(offset))) return false;
         for (int point = 0; point < movedCount; point++) state.set(point, before.get(point).offset(offset));
      } else {
         state.set(index, target);
      }
      if (context.validVolume()) return true;
      for (int point = 0; point < before.size(); point++) state.set(point, before.get(point));
      return false;
   }

   private static Drag constraint(Context context, int index) {
      int baseCount = context.baseClosed() ? context.state().prismBasePointCount : context.state().points().size();
      if (baseCount < 3) return new Drag(index, null, null, OperationPointDragConstraint.FREE);
      List<BlockPos> base = context.state().points().subList(0, baseCount);
      SelectionPrism.GridPlane plane = index < baseCount ? SelectionPrism.gridPlane(base) : null;
      SelectionPrism.GridLine line = index == 0 || context.baseClosed() && index >= baseCount
         ? SelectionPrism.heightGridLine(base) : null;
      return new Drag(index, plane, line, OperationPointDragConstraint.FREE);
   }
   private static ConstrainedPoint snapNearest(Drag drag, BlockPos target) {
      if (drag.plane() == null) return drag.line() == null
         ? new ConstrainedPoint(target, OperationPointDragConstraint.FREE)
         : new ConstrainedPoint(drag.line().snap(target), OperationPointDragConstraint.LINE);
      BlockPos planar = drag.plane().snap(target);
      if (drag.line() == null) return new ConstrainedPoint(planar, OperationPointDragConstraint.PLANE);
      BlockPos linear = drag.line().snap(target);
      return target.distSqr(linear) <= target.distSqr(planar)
         ? new ConstrainedPoint(linear, OperationPointDragConstraint.LINE)
         : new ConstrainedPoint(planar, OperationPointDragConstraint.PLANE);
   }
   private static OperationPointDragConstraint available(Drag drag, OperationPointDragConstraint requested) {
      if (requested == OperationPointDragConstraint.LINE && drag.line() != null) return requested;
      if (requested == OperationPointDragConstraint.PLANE && drag.plane() != null) return requested;
      if (drag.plane() != null) return OperationPointDragConstraint.PLANE;
      return drag.line() == null ? OperationPointDragConstraint.FREE : OperationPointDragConstraint.LINE;
   }

   private record Drag(int pointIndex, SelectionPrism.GridPlane plane, SelectionPrism.GridLine line,
      OperationPointDragConstraint activeConstraint) {
      Drag rebase(BlockPos point, OperationPointDragConstraint constraint) {
         return new Drag(pointIndex,
            constraint == OperationPointDragConstraint.PLANE && plane != null ? plane.through(point) : plane,
            constraint == OperationPointDragConstraint.LINE && line != null ? line.through(point) : line, constraint);
      }
   }
   private record ConstrainedPoint(BlockPos target, OperationPointDragConstraint constraint) {}
}
