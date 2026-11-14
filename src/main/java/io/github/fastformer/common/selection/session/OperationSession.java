package io.github.fastformer.common.selection.session;

import io.github.fastformer.core.math.Axis;

import java.util.function.Predicate;
import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.common.history.SessionHistoryLimits;

import io.github.fastformer.common.workspace.model.OperationConflictMode;

import io.github.fastformer.common.selection.OperationStackRegion;

import io.github.fastformer.common.selection.OperationStageMode;

import io.github.fastformer.common.selection.OperationMode;

import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionStage;
import io.github.fastformer.common.selection.OperationSelectionVolume;

import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import static io.github.fastformer.common.selection.session.OperationTransformState.addAxis;
import static io.github.fastformer.common.selection.session.OperationTransformState.clampOffset;
import static io.github.fastformer.common.selection.session.OperationTransformState.legacyStackVector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class OperationSession {
   private final OperationSelectionPoints selectionPointStates = new OperationSelectionPoints();
   private BlockPos minOffset = BlockPos.ZERO;
   private BlockPos maxOffset = BlockPos.ZERO;
   /** Current AABB; the two selection points remain user input records. */
   private BlockPos cuboidMinPoint;
   private BlockPos cuboidMaxPoint;
   private int hullInflation;
   private OperationSelectionMode selectionMode = OperationSelectionMode.CUBOID;
   private final OperationTransformState transformState = new OperationTransformState();
   private OperationConflictMode conflictMode = OperationConflictMode.REPLACE;
   private boolean extend;
   private boolean selectionConfirmed;
   private final OperationPointEditing pointEditing = new OperationPointEditing();
   private final OperationEditHistory<SessionSnapshot> history;
   private SessionSnapshot transformBaseline;

   public OperationSession() {
      this(OperationSelectionMode.CUBOID, SessionHistoryLimits.DEFAULT_SESSION_UNDO_HISTORY_LIMIT);
   }

   public OperationSession(OperationSelectionMode initialMode) {
      this(initialMode, SessionHistoryLimits.DEFAULT_SESSION_UNDO_HISTORY_LIMIT);
   }

   public OperationSession(OperationSelectionMode initialMode, int historyLimit) {
      this.selectionMode = initialMode == null || initialMode == OperationSelectionMode.CONVEX_HULL
         ? OperationSelectionMode.CUBOID
         : initialMode;
      this.history = new OperationEditHistory<>(historyLimit);
   }

   public List<BlockPos> points() {
      return this.selectionPoints().points();
   }

   public BlockPos first() {
      return this.selectionPoints().first;
   }

   public BlockPos second() {
      return this.selectionPoints().second;
   }

   public BlockPos cuboidMinPoint() {
      return this.selectionMode == OperationSelectionMode.CUBOID && this.selectionPoints().first != null
         && this.selectionPoints().second != null ? this.cuboidMin() : null;
   }

   public BlockPos cuboidMaxPoint() {
      return this.selectionMode == OperationSelectionMode.CUBOID && this.selectionPoints().first != null
         && this.selectionPoints().second != null ? this.cuboidMax() : null;
   }

   public OperationSelectionVolume currentSelectionVolume() {
      if (!this.selectionReady()) return null;
      if (this.selectionMode == OperationSelectionMode.CUBOID) {
         BlockPos min = this.cuboidMin();
         BlockPos max = this.cuboidMax();
         return new OperationSelectionVolume(
            OperationSelectionMode.CUBOID,
            new net.minecraft.world.phys.AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0),
            null, List.of(), 0, this.first(), this.second()
         );
      }
      return OperationSelectionVolume.create(
         this.selectionMode, this.points(), this.selectionPoints().prismBasePointCount,
         this.minOffset, this.maxOffset, this.hullInflation
      );
   }

   public boolean hasFirst() {
      return this.selectionPoints().first != null;
   }

   public boolean hasSecond() {
      return this.selectionPoints().second != null;
   }

   public List<BlockPos> extraPoints() {
      return List.copyOf(this.selectionPoints().extraPoints);
   }

   public BlockPos minOffset() {
      return this.minOffset;
   }

   public BlockPos maxOffset() {
      return this.maxOffset;
   }

   public int hullInflation() {
      return this.hullInflation;
   }

   public OperationSelectionMode selectionMode() {
      return this.selectionMode;
   }

   public void setSelectionMode(OperationSelectionMode selectionMode) {
      if (this.selectionConfirmed) {
         return;
      }
      boolean changedMode = selectionMode != this.selectionMode;
      if (selectionMode != this.selectionMode
         && (selectionMode == OperationSelectionMode.PRISM || this.selectionMode == OperationSelectionMode.PRISM)) {
         this.selectionPointStates.prism().clear();
      }
      this.selectionMode = selectionMode;
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      this.cuboidMinPoint = null;
      this.cuboidMaxPoint = null;
      this.hullInflation = 0;
      this.extend = false;
      this.selectionConfirmed = false;
      this.pointEditing.clear();
      if (changedMode) {
         this.clearAdjustments();
         this.history.clearStacks();
      }
   }

   public void cycleSelectionMode() {
      // Interactive tool cycling is client-owned. Old server drafts can return to cuboid.
      this.setSelectionMode(OperationSelectionMode.CUBOID);
   }

   public OperationSelectionStage selectionStage() {
      int pointCount = this.points().size();
      if (this.selectionMode == OperationSelectionMode.CUBOID) {
         return this.selectionPoints().first != null && this.selectionPoints().second != null ? OperationSelectionStage.READY : OperationSelectionStage.EXTENT;
      }
      if (this.selectionMode == OperationSelectionMode.PRISM) {
         if (this.selectionPoints().prismBasePointCount == 0) {
            return pointCount < 2 ? OperationSelectionStage.FIRST_EDGE : OperationSelectionStage.FACE;
         }
         return pointCount > this.selectionPoints().prismBasePointCount ? OperationSelectionStage.READY : OperationSelectionStage.HEIGHT;
      }
      return this.selectionMode.stage(pointCount);
   }

   public boolean selectionReady() {
      return this.selectionStage() == OperationSelectionStage.READY;
   }

   public boolean needsSelectionPoint() {
      return !this.selectionReady();
   }

   public int prismBasePointCount() {
      return this.selectionPoints().prismBasePointCount;
   }

   public boolean prismBaseClosed() {
      return this.selectionMode == OperationSelectionMode.PRISM && this.selectionPoints().prismBasePointCount >= 3;
   }

   public boolean closePrismBase() {
      if (this.selectionConfirmed
         || this.selectionMode != OperationSelectionMode.PRISM
         || this.prismBaseClosed()
         || this.points().size() < 3) {
         return false;
      }
      this.selectionPoints().prismBasePointCount = this.points().size();
      this.selectionConfirmed = false;
      return true;
   }

   public int selectedPointIndex() {
      return this.selectionPoints().selectedPointIndex;
   }

   public boolean selectPoint(int index) {
      if (this.selectionConfirmed || this.selectionMode != OperationSelectionMode.PRISM || index < 0 || index >= this.points().size()) {
         return false;
      }
      this.selectionPoints().selectedPointIndex = this.selectionPoints().selectedPointIndex == index ? -1 : index;
      return true;
   }

   public boolean focusPoint(int index) {
      if (this.selectionConfirmed || this.selectionMode != OperationSelectionMode.PRISM || index < 0 || index >= this.points().size()) {
         return false;
      }
      this.selectionPoints().selectedPointIndex = index;
      return true;
   }

   public boolean removePoint(int index) {
      List<BlockPos> points = new ArrayList<>(this.points());
      if (this.selectionConfirmed || this.selectionMode != OperationSelectionMode.PRISM || index < 0 || index >= points.size()) {
         return false;
      }
      points.remove(index);
      int oldBaseCount = this.selectionPoints().prismBasePointCount;
      int newBaseCount = oldBaseCount > 0 && index < oldBaseCount ? oldBaseCount - 1 : oldBaseCount;
      this.selectionPoints().first = points.isEmpty() ? null : points.getFirst();
      this.selectionPoints().second = points.size() < 2 ? null : points.get(1);
      this.selectionPoints().extraPoints.clear();
      if (points.size() > 2) {
         this.selectionPoints().extraPoints.addAll(points.subList(2, points.size()));
      }
      this.selectionPoints().prismBasePointCount = newBaseCount >= 3 && points.size() >= newBaseCount ? newBaseCount : 0;
      this.selectionPoints().selectedPointIndex = -1;
      this.pointEditing.clear();
      this.selectionConfirmed = false;
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      if (!this.selectionReady()) {
         this.clearAdjustments();
         this.history.clearStacks();
      }
      return true;
   }

   public boolean insertPoint(int insertionIndex, BlockPos point) {
      List<BlockPos> before = this.points();
      if (this.selectionConfirmed || this.selectionMode != OperationSelectionMode.PRISM || point == null || before.contains(point)) {
         return false;
      }
      int oldBaseCount = this.selectionPoints().prismBasePointCount;
      boolean closed = oldBaseCount >= 3;
      int baseCount = closed ? oldBaseCount : before.size();
      int maximumInsertion = closed ? baseCount : baseCount - 1;
      if (insertionIndex < 1 || insertionIndex > maximumInsertion) {
         return false;
      }

      List<BlockPos> inserted = new ArrayList<>(before);
      inserted.add(insertionIndex, point.immutable());
      int oldSelected = this.selectionPoints().selectedPointIndex;
      int newSelected = oldSelected >= insertionIndex ? oldSelected + 1 : oldSelected;
      this.replacePoints(inserted, closed ? oldBaseCount + 1 : 0, newSelected);
      if (this.selectionReady() && OperationSelectionVolume.create(
         this.selectionMode, this.points(), this.selectionPoints().prismBasePointCount,
         this.minOffset, this.maxOffset, this.hullInflation
      ) == null) {
         this.replacePoints(before, oldBaseCount, oldSelected);
         return false;
      }
      this.pointEditing.clear();
      this.selectionConfirmed = false;
      return true;
   }

   public OperationMode mode() {
      return this.transformState.mode;
   }

   public OperationConflictMode conflictMode() {
      return this.conflictMode;
   }

   public void setConflictMode(OperationConflictMode conflictMode) {
      this.conflictMode = conflictMode;
   }

   public BlockPos translation() {
      return this.transformState.translation;
   }

   public BlockPos stackVector() {
      return this.transformState.stackVector;
   }

   public OperationStackRegion stackRegion() {
      return this.transformState.stackRegion;
   }

   public boolean operationReady() {
      return this.selectionReady();
   }

   public boolean adjustmentStarted() {
      return this.transformState.adjustmentStarted;
   }

   public boolean transformActive() {
      return this.transformState.active();
   }

   public OperationStageMode stageMode() {
      return this.transformState.stageMode;
   }

   public Vec3 rotation() {
      return this.transformState.rotation;
   }

   public void cycleStageMode() {
      if (this.operationReady()) {
         this.transformState.stageMode = this.transformState.stageMode.next();
      }
   }

   public boolean selectionConfirmed() {
      return this.selectionConfirmed;
   }

   public boolean confirmSelection() {
      if (!this.selectionReady()) {
         return false;
      }
      this.transformState.stageMode = OperationStageMode.TRANSFORM;
      this.selectionConfirmed = true;
      return true;
   }

   public boolean beginTransform(AxisGizmo.Operation operation, Axis axis, int direction) {
      if (!this.transformState.begin(operation, axis, direction, this.operationReady())) return false;
      this.transformBaseline = this.snapshot();
      return true;
   }

   public boolean updateTransform(int totalSteps) {
      return this.transformState.update(totalSteps);
   }

   public boolean finishTransform() {
      SessionSnapshot before = this.transformBaseline;
      this.transformBaseline = null;
      if (!this.transformState.finish()) return false;
      this.history.record(before);
      return true;
   }

   public boolean undoAdjustment() {
      return this.undoStep();
   }

   /** Expands a cuboid selection just enough to contain the temporary point. */
   public boolean expandTo(BlockPos point) {
      if (this.selectionConfirmed || this.selectionMode != OperationSelectionMode.CUBOID || this.selectionPoints().first == null || this.selectionPoints().second == null || point == null) {
         return false;
      }
      BlockPos oldMin = this.cuboidMin();
      BlockPos oldMax = this.cuboidMax();
      BlockPos newMin = new BlockPos(
         Math.min(oldMin.getX(), point.getX()),
         Math.min(oldMin.getY(), point.getY()),
         Math.min(oldMin.getZ(), point.getZ())
      );
      BlockPos newMax = new BlockPos(
         Math.max(oldMax.getX(), point.getX()),
         Math.max(oldMax.getY(), point.getY()),
         Math.max(oldMax.getZ(), point.getZ())
      );
      if (newMin.equals(oldMin) && newMax.equals(oldMax)) {
         return false;
      }
      this.cuboidMinPoint = newMin.immutable();
      this.cuboidMaxPoint = newMax.immutable();
      this.extend = true;
      this.selectionConfirmed = false;
      return true;
   }

   public void scroll(BlockPos axisStep, boolean stack) {
      this.transformState.scroll(axisStep, stack, this.selectionConfirmed);
   }

   public void scroll(BlockPos axisStep) {
      this.scroll(axisStep, this.transformState.mode == OperationMode.STACK);
   }

   public boolean adjustOperationTransform(
      io.github.fastformer.common.geometry.gizmo.AxisGizmo.Operation operation,
      Axis axis,
      int steps
   ) {
      return this.transformState.adjust(operation, axis, steps, this.selectionConfirmed);
   }

   public boolean extend() {
      return this.extend;
   }

   public void setExtend(boolean extend) {
      this.extend = extend;
   }

   public boolean extend(int axis, boolean positive, int steps) {
      if (this.selectionConfirmed || steps == 0) {
         return false;
      }
      if (this.selectionMode == OperationSelectionMode.CONVEX_HULL) {
         int oldInflation = this.hullInflation;
         this.hullInflation = Math.clamp(this.hullInflation + steps, -128, 128);
         if (OperationGeometry.bounds(this.points(), this.minOffset, this.maxOffset, this.hullInflation) == null) {
            this.hullInflation = oldInflation;
            return false;
         }
         this.extend = true;
         this.selectionConfirmed = false;
         return true;
      }
      if (this.selectionMode == OperationSelectionMode.CUBOID) {
         if (this.selectionPoints().first == null || this.selectionPoints().second == null || axis < 0 || axis > 2) {
            return false;
         }
         BlockPos oldMin = this.cuboidMin();
         BlockPos oldMax = this.cuboidMax();
         BlockPos newMin = positive ? oldMin : addAxis(oldMin, axis, -steps);
         BlockPos newMax = positive ? addAxis(oldMax, axis, steps) : oldMax;
         if (newMin.getX() > newMax.getX() || newMin.getY() > newMax.getY() || newMin.getZ() > newMax.getZ()) {
            return false;
         }
         this.cuboidMinPoint = newMin.immutable();
         this.cuboidMaxPoint = newMax.immutable();
         this.extend = true;
         this.selectionConfirmed = false;
         return true;
      }
      BlockPos oldMinOffset = this.minOffset;
      BlockPos oldMaxOffset = this.maxOffset;
      if (positive) {
         this.maxOffset = clampOffset(addAxis(this.maxOffset, axis, steps));
      } else {
         this.minOffset = clampOffset(addAxis(this.minOffset, axis, -steps));
      }
      if (OperationSelectionVolume.create(
         this.selectionMode, this.points(), this.selectionPoints().prismBasePointCount, this.minOffset, this.maxOffset, this.hullInflation
      ) == null) {
         this.minOffset = oldMinOffset;
         this.maxOffset = oldMaxOffset;
         return false;
      }
      this.extend = true;
      this.selectionConfirmed = false;
      return true;
   }

   public boolean moveSelection(int axis, int steps) {
      if (this.selectionConfirmed || steps == 0 || axis < 0 || axis > 2 || this.selectionPoints().first == null) {
         return false;
      }
      BlockPos offset = addAxis(BlockPos.ZERO, axis, steps);
      if (this.selectionMode == OperationSelectionMode.CUBOID) {
         if (this.cuboidMinPoint == null || this.cuboidMaxPoint == null) {
            return false;
         }
         this.cuboidMinPoint = this.cuboidMinPoint.offset(offset).immutable();
         this.cuboidMaxPoint = this.cuboidMaxPoint.offset(offset).immutable();
         return true;
      }
      this.selectionPoints().first = this.selectionPoints().first.offset(offset).immutable();
      if (this.selectionPoints().second != null) {
         this.selectionPoints().second = this.selectionPoints().second.offset(offset).immutable();
      }
      for (int index = 0; index < this.selectionPoints().extraPoints.size(); index++) {
         this.selectionPoints().extraPoints.set(index, this.selectionPoints().extraPoints.get(index).offset(offset).immutable());
      }
      this.selectionConfirmed = false;
      return true;
   }

   public boolean moveSelectedPoint(int axis, int steps) {
      return this.movePoint(this.selectionPoints().selectedPointIndex, axis, steps);
   }

   public boolean movePoint(int pointIndex, int axis, int steps) {
      return this.pointEditing.move(pointContext(), pointIndex, axis, steps);
   }

   public boolean dragPointTo(int pointIndex, BlockPos target, boolean finish) {
      return this.pointEditing.drag(pointContext(), pointIndex, target, finish);
   }

   public boolean dragPointTo(
      int pointIndex,
      BlockPos target,
      OperationPointDragConstraint requestedConstraint,
      boolean finish
   ) {
      return dragPointTo(pointIndex, target, requestedConstraint, finish, point -> true);
   }

   public boolean dragPointTo(int pointIndex, BlockPos target,
      OperationPointDragConstraint requestedConstraint, boolean finish,
      Predicate<BlockPos> targetValidator) {
      return this.pointEditing.drag(pointContext(), pointIndex, target, requestedConstraint, finish, targetValidator);
   }

   private OperationPointEditing.Context pointContext() {
      return new OperationPointEditing.Context(selectionMode, selectionPoints(), selectionConfirmed, minOffset, maxOffset, hullInflation);
   }

   private void replacePoints(List<BlockPos> points, int prismBasePointCount, int selectedPointIndex) {
      this.selectionPoints().first = points.isEmpty() ? null : points.getFirst().immutable();
      this.selectionPoints().second = points.size() < 2 ? null : points.get(1).immutable();
      this.selectionPoints().extraPoints.clear();
      if (points.size() > 2) {
         this.selectionPoints().extraPoints.addAll(points.subList(2, points.size()).stream().map(BlockPos::immutable).toList());
      }
      this.selectionPoints().prismBasePointCount = prismBasePointCount;
      this.selectionPoints().selectedPointIndex = selectedPointIndex;
   }

   public void setFirst(BlockPos point) {
      if (this.selectionConfirmed) {
         return;
      }
      this.selectionPoints().first = point.immutable();
      this.clearTransientSelection();
      this.recomputeCuboidBounds();
   }

   public void setSecond(BlockPos point) {
      if (this.selectionConfirmed) {
         return;
      }
      this.selectionPoints().second = point.immutable();
      this.clearTransientSelection();
      this.recomputeCuboidBounds();
   }

   public boolean addExtraPoint(BlockPos point) {
      return !this.selectionConfirmed
         && (this.selectionMode == OperationSelectionMode.CONVEX_HULL
         || this.selectionMode == OperationSelectionMode.PRISM && !this.prismBaseClosed())
         && this.appendExtraPoint(point);
   }

   private boolean appendExtraPoint(BlockPos point) {
      BlockPos immutable = point.immutable();
      if (immutable.equals(this.selectionPoints().first) || immutable.equals(this.selectionPoints().second) || this.selectionPoints().extraPoints.contains(immutable)) {
         return false;
      }
      this.selectionPoints().extraPoints.add(immutable);
      return true;
   }

   public boolean addSelectionPoint(BlockPos point) {
      if (this.selectionConfirmed || this.selectionReady()) {
         return false;
      }
      if (this.selectionMode == OperationSelectionMode.CUBOID && !this.hasFirst()) {
         this.setFirst(point);
         return true;
      }
      if (this.selectionPoints().second == null) {
         this.setSecond(point);
         return true;
      }
      if (this.selectionMode == OperationSelectionMode.PRISM) {
         if (!this.prismBaseClosed() || this.points().size() == this.selectionPoints().prismBasePointCount) {
            return this.appendExtraPoint(point);
         }
         return false;
      }
      return this.appendExtraPoint(point);
   }

   public boolean undoStep() {
      this.commitEdit();
      SessionSnapshot previous = this.history.undo(this.snapshot());
      if (previous == null) {
         return false;
      }
      this.restore(previous);
      return true;
   }

   public boolean canUndoStep() {
      return this.history.canUndo(this.snapshot());
   }

   public boolean redoStep() {
      this.commitEdit();
      SessionSnapshot next = this.history.redo(this.snapshot());
      if (next == null) {
         return false;
      }
      this.restore(next);
      return true;
   }

   public boolean canRedoStep() {
      return this.history.canRedo();
   }

   public void updateHistoryLimit(int historyLimit) {
      this.history.updateLimit(historyLimit);
   }

   public void beginEdit() {
      this.history.begin(this.snapshot());
   }

   public boolean commitEdit() {
      return this.history.commit(this.snapshot());
   }

   public void discardPendingEdit() {
      this.history.discardPending();
   }

   public void clear() {
      this.selectionPointStates.clear();
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      this.hullInflation = 0;
      this.transformState.clear();
      this.extend = false;
      this.selectionConfirmed = false;
      this.pointEditing.clear();
      this.history.clear();
      this.transformState.adjustmentStarted = false;
      this.transformBaseline = null;
   }

   private void clearTransientSelection() {
      this.selectionPoints().extraPoints.clear();
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      this.hullInflation = 0;
      this.clearAdjustments();
      this.extend = false;
      this.selectionConfirmed = false;
      this.selectionPoints().prismBasePointCount = 0;
      this.selectionPoints().selectedPointIndex = -1;
      this.pointEditing.clear();
   }

   private void clearAdjustments() {
      this.transformState.clear();
      this.transformBaseline = null;
   }

   private SelectionPointState selectionPoints() {
      return this.selectionPointStates.forMode(this.selectionMode);
   }

   private BlockPos cuboidMin() {
      if (this.cuboidMinPoint != null) return this.cuboidMinPoint;
      return new BlockPos(
         Math.min(this.selectionPoints().first.getX(), this.selectionPoints().second.getX()),
         Math.min(this.selectionPoints().first.getY(), this.selectionPoints().second.getY()),
         Math.min(this.selectionPoints().first.getZ(), this.selectionPoints().second.getZ())
      );
   }

   private BlockPos cuboidMax() {
      if (this.cuboidMaxPoint != null) return this.cuboidMaxPoint;
      return new BlockPos(
         Math.max(this.selectionPoints().first.getX(), this.selectionPoints().second.getX()),
         Math.max(this.selectionPoints().first.getY(), this.selectionPoints().second.getY()),
         Math.max(this.selectionPoints().first.getZ(), this.selectionPoints().second.getZ())
      );
   }

   private void recomputeCuboidBounds() {
      if (this.selectionMode != OperationSelectionMode.CUBOID
         || this.selectionPoints().first == null || this.selectionPoints().second == null) {
         this.cuboidMinPoint = null;
         this.cuboidMaxPoint = null;
         return;
      }
      BlockPos first = this.selectionPoints().first;
      BlockPos second = this.selectionPoints().second;
      this.cuboidMinPoint = new BlockPos(
         Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ())
      );
      this.cuboidMaxPoint = new BlockPos(
         Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ())
      );
   }

   private SessionSnapshot snapshot() {
      return new SessionSnapshot(
         this.selectionPointStates.cuboid().snapshot(),
         this.selectionPointStates.prism().snapshot(),
         this.selectionPointStates.hull().snapshot(),
         this.minOffset,
         this.maxOffset,
         this.cuboidMinPoint,
         this.cuboidMaxPoint,
         this.hullInflation,
         this.selectionMode,
         this.conflictMode,
         this.selectionConfirmed,
         this.transformState.mode,
         this.transformState.translation,
         this.transformState.stackRegion,
         this.transformState.rotation,
         this.transformState.stageMode,
         this.transformState.adjustmentStarted
      );
   }

   private void restore(SessionSnapshot snapshot) {
      this.clearAdjustments();
      this.selectionPointStates.cuboid().restore(snapshot.cuboidPoints());
      this.selectionPointStates.prism().restore(snapshot.prismPoints());
      this.selectionPointStates.hull().restore(snapshot.hullPoints());
      this.minOffset = snapshot.minOffset();
      this.maxOffset = snapshot.maxOffset();
      this.cuboidMinPoint = snapshot.cuboidMinPoint();
      this.cuboidMaxPoint = snapshot.cuboidMaxPoint();
      this.hullInflation = snapshot.hullInflation();
      this.selectionMode = snapshot.selectionMode();
      this.conflictMode = snapshot.conflictMode();
      this.selectionConfirmed = snapshot.selectionConfirmed();
      this.transformState.mode = snapshot.mode();
      this.transformState.translation = snapshot.translation();
      this.transformState.stackRegion = snapshot.stackRegion();
      this.transformState.stackVector = legacyStackVector(this.transformState.stackRegion);
      this.transformState.rotation = snapshot.rotation();
      this.transformState.stageMode = snapshot.stageMode();
      this.transformState.adjustmentStarted = snapshot.adjustmentStarted();
      this.extend = false;
      this.pointEditing.clear();
   }

   private record SessionSnapshot(
      SelectionPointState.PointStateSnapshot cuboidPoints,
      SelectionPointState.PointStateSnapshot prismPoints,
      SelectionPointState.PointStateSnapshot hullPoints,
      BlockPos minOffset,
      BlockPos maxOffset,
      BlockPos cuboidMinPoint,
      BlockPos cuboidMaxPoint,
      int hullInflation,
      OperationSelectionMode selectionMode,
      OperationConflictMode conflictMode,
      boolean selectionConfirmed,
      OperationMode mode,
      BlockPos translation,
      OperationStackRegion stackRegion,
      Vec3 rotation,
      OperationStageMode stageMode,
      boolean adjustmentStarted
   ) {
   }

}
