package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class OperationSession implements SessionLifecycle {
   private static final int MAX_POINT_DRAG_DELTA = 128;
   private final SelectionPointState cuboidPoints = new SelectionPointState();
   private final SelectionPointState prismPoints = new SelectionPointState();
   private final SelectionPointState hullPoints = new SelectionPointState();
   private int historyLimit;
   private BlockPos minOffset = BlockPos.ZERO;
   private BlockPos maxOffset = BlockPos.ZERO;
   /** Current AABB; the two selection points remain user input records. */
   private BlockPos cuboidMinPoint;
   private BlockPos cuboidMaxPoint;
   private int hullInflation;
   private OperationSelectionMode selectionMode = OperationSelectionMode.CUBOID;
   private OperationMode mode = OperationMode.MOVE;
   private OperationStageMode stageMode = OperationStageMode.TRANSFORM;
   private OperationConflictMode conflictMode = OperationConflictMode.REPLACE;
   private BlockPos translation = BlockPos.ZERO;
   private BlockPos stackVector = BlockPos.ZERO;
   private OperationStackRegion stackRegion = OperationStackRegion.origin();
   private Vec3 rotation = Vec3.ZERO;
   private boolean extend;
   private boolean selectionConfirmed;
   private PointDragState pointDragState;
   private final Deque<SessionSnapshot> undoHistory = new ArrayDeque<>();
   private final Deque<SessionSnapshot> redoHistory = new ArrayDeque<>();
   private SessionSnapshot pendingEdit;
   private boolean adjustmentStarted;
   private TransformDrag transformDrag;

   public OperationSession() {
      this(OperationSelectionMode.CUBOID, FastPlaceSettings.DEFAULT_SESSION_UNDO_HISTORY_LIMIT);
   }

   public OperationSession(OperationSelectionMode initialMode) {
      this(initialMode, FastPlaceSettings.DEFAULT_SESSION_UNDO_HISTORY_LIMIT);
   }

   public OperationSession(OperationSelectionMode initialMode, int historyLimit) {
      this.selectionMode = initialMode == null || initialMode == OperationSelectionMode.CONVEX_HULL
         ? OperationSelectionMode.CUBOID
         : initialMode;
      this.historyLimit = Math.clamp(historyLimit, 1, FastPlaceSettings.MAX_UNDO_HISTORY_LIMIT);
   }

   public List<BlockPos> points() {
      List<BlockPos> points = new ArrayList<>();
      if (this.selectionPoints().first != null) {
         points.add(this.selectionPoints().first);
      }
      if (this.selectionPoints().second != null) {
         points.add(this.selectionPoints().second);
      }
      points.addAll(this.selectionPoints().extraPoints);
      return List.copyOf(points);
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
         this.prismPoints.clear();
      }
      this.selectionMode = selectionMode;
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      this.cuboidMinPoint = null;
      this.cuboidMaxPoint = null;
      this.hullInflation = 0;
      this.extend = false;
      this.selectionConfirmed = false;
      this.pointDragState = null;
      if (changedMode) {
         this.clearAdjustments();
         this.undoHistory.clear();
         this.redoHistory.clear();
      }
   }

   public void cycleSelectionMode() {
      this.setSelectionMode(this.selectionMode.next());
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
      this.pointDragState = null;
      this.selectionConfirmed = false;
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      if (!this.selectionReady()) {
         this.clearAdjustments();
         this.undoHistory.clear();
         this.redoHistory.clear();
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
      this.pointDragState = null;
      this.selectionConfirmed = false;
      return true;
   }

   public OperationMode mode() {
      return this.mode;
   }

   public OperationConflictMode conflictMode() {
      return this.conflictMode;
   }

   public void setConflictMode(OperationConflictMode conflictMode) {
      this.conflictMode = conflictMode;
   }

   public BlockPos translation() {
      return this.translation;
   }

   public BlockPos stackVector() {
      return this.stackVector;
   }

   public OperationStackRegion stackRegion() {
      return this.stackRegion;
   }

   public boolean operationReady() {
      return this.selectionReady();
   }

   public boolean adjustmentStarted() {
      return this.adjustmentStarted;
   }

   public boolean transformActive() {
      return this.transformDrag != null;
   }

   public OperationStageMode stageMode() {
      return this.stageMode;
   }

   public Vec3 rotation() {
      return this.rotation;
   }

   public void cycleStageMode() {
      if (this.operationReady()) {
         this.stageMode = this.stageMode.next();
      }
   }

   public boolean selectionConfirmed() {
      return this.selectionConfirmed;
   }

   public boolean confirmSelection() {
      if (!this.selectionReady()) {
         return false;
      }
      this.stageMode = OperationStageMode.TRANSFORM;
      this.selectionConfirmed = true;
      return true;
   }

   public boolean beginTransform(AxisGizmo.Operation operation, AxisGizmo.Axis axis, int direction) {
      if (!this.operationReady() || operation == null || axis == null || !this.acceptsTransform(operation)) {
         return false;
      }
      int normalizedDirection = Integer.compare(direction, 0);
      if (operation != AxisGizmo.Operation.ROTATE && normalizedDirection == 0) {
         return false;
      }
      this.transformDrag = new TransformDrag(operation, axis, normalizedDirection, this.snapshot(), this.transformSnapshot());
      return true;
   }

   public boolean updateTransform(int totalSteps) {
      TransformDrag drag = this.transformDrag;
      if (drag == null) {
         return false;
      }
      this.restoreTransform(drag.baseline());
      int directedSteps = drag.operation() == AxisGizmo.Operation.ROTATE && drag.direction() == 0
         ? totalSteps
         : drag.direction() * Math.max(0, totalSteps);
      switch (drag.operation()) {
         case MOVE -> {
            if (directedSteps != 0) {
               this.mode = OperationMode.MOVE;
            }
            this.translation = clampOffset(addAxis(drag.baseline().translation(), drag.axis().ordinal(), directedSteps));
         }
         case SCALE -> {
            if (directedSteps != 0) {
               this.mode = OperationMode.STACK;
            }
            this.stackRegion = drag.baseline().stackRegion().repeat(drag.axis(), drag.direction(), Math.max(0, totalSteps));
            this.stackVector = legacyStackVector(this.stackRegion);
         }
         case ROTATE -> {
            double radians = directedSteps * Math.PI * 2.0 / 1024.0;
            this.rotation = switch (drag.axis()) {
               case X -> drag.baseline().rotation().add(radians, 0.0, 0.0);
               case Y -> drag.baseline().rotation().add(0.0, radians, 0.0);
               case Z -> drag.baseline().rotation().add(0.0, 0.0, radians);
            };
         }
      }
      this.adjustmentStarted = drag.baseline().adjustmentStarted()
         || !this.transformSnapshot().sameValues(drag.baseline());
      return true;
   }

   public boolean finishTransform() {
      TransformDrag drag = this.transformDrag;
      this.transformDrag = null;
      if (drag == null || this.transformSnapshot().sameValues(drag.baseline())) {
         if (drag != null) {
            this.restoreTransform(drag.baseline());
         }
         return false;
      }
      pushHistory(this.undoHistory, drag.sessionBaseline());
      this.redoHistory.clear();
      this.adjustmentStarted = true;
      return true;
   }

   public boolean undoAdjustment() {
      return this.undoStep();
   }

   private boolean acceptsTransform(AxisGizmo.Operation operation) {
      return this.stageMode == OperationStageMode.TRANSFORM;
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
      if (!this.selectionConfirmed || this.stageMode != OperationStageMode.TRANSFORM) {
         return;
      }
      this.mode = stack ? OperationMode.STACK : OperationMode.MOVE;
      if (!stack) {
         this.translation = clampOffset(this.translation.offset(axisStep));
      } else {
         this.stackVector = clampOffset(this.stackVector.offset(axisStep));
      }
   }

   public void scroll(BlockPos axisStep) {
      this.scroll(axisStep, this.mode == OperationMode.STACK);
   }

   public boolean adjustOperationTransform(
      io.github.fastformer.fastplace.geometry.AxisGizmo.Operation operation,
      io.github.fastformer.fastplace.geometry.AxisGizmo.Axis axis,
      int steps
   ) {
      if (!this.selectionConfirmed || steps == 0 || operation == null || axis == null) {
         return false;
      }
      int axisIndex = axis.ordinal();
      if (this.stageMode == OperationStageMode.TRANSFORM && operation != io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.ROTATE) {
         this.scroll(addAxis(BlockPos.ZERO, axisIndex, steps), operation == io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.SCALE);
         return true;
      }
      if (this.stageMode == OperationStageMode.TRANSFORM && operation == io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.ROTATE) {
         double radians = steps * Math.PI * 2.0 / 1024.0;
         this.rotation = switch (axis) {
            case X -> this.rotation.add(radians, 0.0, 0.0);
            case Y -> this.rotation.add(0.0, radians, 0.0);
            case Z -> this.rotation.add(0.0, 0.0, radians);
         };
         return true;
      }
      return false;
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
      this.selectionPoints().first = this.selectionPoints().first.offset(offset).immutable();
      if (this.selectionPoints().second != null) {
         this.selectionPoints().second = this.selectionPoints().second.offset(offset).immutable();
      }
      for (int index = 0; index < this.selectionPoints().extraPoints.size(); index++) {
         this.selectionPoints().extraPoints.set(index, this.selectionPoints().extraPoints.get(index).offset(offset).immutable());
      }
      if (this.selectionMode == OperationSelectionMode.CUBOID) {
         this.minOffset = BlockPos.ZERO;
         this.maxOffset = BlockPos.ZERO;
         this.recomputeCuboidBounds();
      }
      this.selectionConfirmed = false;
      return true;
   }

   public boolean moveSelectedPoint(int axis, int steps) {
      return this.movePoint(this.selectionPoints().selectedPointIndex, axis, steps);
   }

   public boolean movePoint(int pointIndex, int axis, int steps) {
      if (this.selectionConfirmed || steps == 0 || axis < 0 || axis > 2 || pointIndex < 0) {
         return false;
      }
      List<BlockPos> before = this.points();
      if (pointIndex >= before.size()) {
         return false;
      }
      BlockPos moved = before.get(pointIndex).offset(addAxis(BlockPos.ZERO, axis, steps));
      PointDragState constraint = this.pointDragConstraint(pointIndex);
      return this.movePointTo(
         pointIndex, constrainPointTarget(constraint, before.get(pointIndex), moved), before
      );
   }

   public boolean dragPointTo(int pointIndex, BlockPos target, boolean finish) {
      if (this.selectionConfirmed
         || this.selectionMode != OperationSelectionMode.PRISM
         || target == null
         || pointIndex < 0
         || pointIndex >= this.points().size()) {
         this.pointDragState = null;
         return false;
      }
      boolean started = this.pointDragState == null || this.pointDragState.pointIndex() != pointIndex;
      if (started) {
         this.pointDragState = this.pointDragConstraint(pointIndex);
      }
      this.selectionPoints().selectedPointIndex = pointIndex;
      List<BlockPos> before = this.points();
      ConstrainedPoint constrained = constrainPointTarget(this.pointDragState, before.get(pointIndex), target);
      boolean changed = this.movePointTo(pointIndex, constrained, before);
      if (finish) {
         this.pointDragState = null;
      }
      return started || changed || finish;
   }

   public boolean dragPointTo(
      int pointIndex,
      BlockPos target,
      OperationPointDragConstraint requestedConstraint,
      boolean finish
   ) {
      if (this.selectionConfirmed
         || this.selectionMode != OperationSelectionMode.PRISM
         || target == null
         || pointIndex < 0
         || pointIndex >= this.points().size()) {
         this.pointDragState = null;
         return false;
      }
      boolean started = this.pointDragState == null || this.pointDragState.pointIndex() != pointIndex;
      if (started) {
         this.pointDragState = this.pointDragConstraint(pointIndex);
      }
      this.selectionPoints().selectedPointIndex = pointIndex;
      List<BlockPos> before = this.points();
      BlockPos current = before.get(pointIndex);
      OperationPointDragConstraint constraint = availableConstraint(this.pointDragState, requestedConstraint);
      if (this.pointDragState.activeConstraint() != constraint) {
         this.pointDragState = this.pointDragState.rebase(current, constraint);
      }
      ConstrainedPoint constrained = constrainPointTarget(this.pointDragState, target, constraint);
      boolean changed = this.movePointTo(pointIndex, constrained, before);
      if (finish) {
         this.pointDragState = null;
      }
      return started || changed || finish;
   }

   private boolean movePointTo(int pointIndex, ConstrainedPoint constrained, List<BlockPos> before) {
      BlockPos target = constrained.target();
      BlockPos current = before.get(pointIndex);
      if (current.equals(target)) {
         return false;
      }
      if (Math.abs((long)target.getX() - current.getX()) > MAX_POINT_DRAG_DELTA
         || Math.abs((long)target.getY() - current.getY()) > MAX_POINT_DRAG_DELTA
         || Math.abs((long)target.getZ() - current.getZ()) > MAX_POINT_DRAG_DELTA) {
         return false;
      }
      int baseCount = this.prismBaseClosed() ? this.selectionPoints().prismBasePointCount : before.size();
      boolean moveBase = pointIndex == 0
         && baseCount >= 3
         && constrained.constraint() != OperationPointDragConstraint.FREE;
      if (moveBase) {
         BlockPos offset = target.subtract(current);
         for (int index = 0; index < baseCount; index++) {
            setPoint(index, before.get(index).offset(offset));
         }
         if (constrained.constraint() == OperationPointDragConstraint.PLANE) {
            for (int index = baseCount; index < before.size(); index++) {
               setPoint(index, before.get(index).offset(offset));
            }
         }
      } else {
         setPoint(pointIndex, target);
      }
      if (this.selectionReady() && OperationSelectionVolume.create(
         this.selectionMode, this.points(), this.selectionPoints().prismBasePointCount, this.minOffset, this.maxOffset, this.hullInflation
      ) == null) {
         for (int index = 0; index < before.size(); index++) {
            setPoint(index, before.get(index));
         }
         return false;
      }
      this.selectionConfirmed = false;
      return true;
   }

   private PointDragState pointDragConstraint(int pointIndex) {
      int baseCount = this.prismBaseClosed() ? this.selectionPoints().prismBasePointCount : this.points().size();
      if (baseCount < 3) {
         return new PointDragState(pointIndex, null, null, OperationPointDragConstraint.FREE);
      }
      List<BlockPos> base = this.points().subList(0, baseCount);
      SelectionPrism.GridPlane plane = pointIndex < baseCount ? SelectionPrism.gridPlane(base) : null;
      SelectionPrism.GridLine line = pointIndex == 0 || this.prismBaseClosed() && pointIndex >= baseCount
         ? SelectionPrism.heightGridLine(base)
         : null;
      return new PointDragState(pointIndex, plane, line, OperationPointDragConstraint.FREE);
   }

   private static ConstrainedPoint constrainPointTarget(
      PointDragState constraint, BlockPos current, BlockPos target
   ) {
      if (constraint == null) {
         return new ConstrainedPoint(target, OperationPointDragConstraint.FREE);
      }
      SelectionPrism.GridPlane plane = constraint.plane();
      SelectionPrism.GridLine line = constraint.line();
      if (plane == null) {
         return line == null
            ? new ConstrainedPoint(target, OperationPointDragConstraint.FREE)
            : new ConstrainedPoint(line.snap(target), OperationPointDragConstraint.LINE);
      }
      BlockPos planar = plane.snap(target);
      if (line == null) {
         return new ConstrainedPoint(planar, OperationPointDragConstraint.PLANE);
      }
      BlockPos linear = line.snap(target);
      return target.distSqr(linear) <= target.distSqr(planar)
         ? new ConstrainedPoint(linear, OperationPointDragConstraint.LINE)
         : new ConstrainedPoint(planar, OperationPointDragConstraint.PLANE);
   }

   private static OperationPointDragConstraint availableConstraint(
      PointDragState state, OperationPointDragConstraint requested
   ) {
      if (requested == OperationPointDragConstraint.LINE && state.line() != null) {
         return OperationPointDragConstraint.LINE;
      }
      if (requested == OperationPointDragConstraint.PLANE && state.plane() != null) {
         return OperationPointDragConstraint.PLANE;
      }
      if (state.plane() != null) {
         return OperationPointDragConstraint.PLANE;
      }
      return state.line() == null ? OperationPointDragConstraint.FREE : OperationPointDragConstraint.LINE;
   }

   private static ConstrainedPoint constrainPointTarget(
      PointDragState state, BlockPos target, OperationPointDragConstraint constraint
   ) {
      return switch (constraint) {
         case PLANE -> new ConstrainedPoint(state.plane().snap(target), OperationPointDragConstraint.PLANE);
         case LINE -> new ConstrainedPoint(state.line().snap(target), OperationPointDragConstraint.LINE);
         case FREE -> new ConstrainedPoint(target, OperationPointDragConstraint.FREE);
      };
   }

   private void setPoint(int index, BlockPos point) {
      BlockPos immutable = point.immutable();
      if (index == 0) {
         this.selectionPoints().first = immutable;
      } else if (index == 1) {
         this.selectionPoints().second = immutable;
      } else {
         this.selectionPoints().extraPoints.set(index - 2, immutable);
      }
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

   @Override
   public boolean undoStep() {
      this.commitEdit();
      SessionSnapshot previous = this.undoHistory.pollLast();
      if (previous == null) {
         return false;
      }
      pushHistory(this.redoHistory, this.snapshot());
      this.restore(previous);
      return true;
   }

   @Override
   public boolean canUndoStep() {
      return !this.undoHistory.isEmpty()
         || this.pendingEdit != null && !this.pendingEdit.equals(this.snapshot());
   }

   @Override
   public boolean redoStep() {
      this.commitEdit();
      SessionSnapshot next = this.redoHistory.pollLast();
      if (next == null) {
         return false;
      }
      pushHistory(this.undoHistory, this.snapshot());
      this.restore(next);
      return true;
   }

   @Override
   public boolean canRedoStep() {
      return !this.redoHistory.isEmpty();
   }

   public void updateHistoryLimit(int historyLimit) {
      this.historyLimit = Math.clamp(historyLimit, 1, FastPlaceSettings.MAX_UNDO_HISTORY_LIMIT);
      trimHistory(this.undoHistory, this.historyLimit);
      trimHistory(this.redoHistory, this.historyLimit);
   }

   public void beginEdit() {
      if (this.pendingEdit == null) {
         this.pendingEdit = this.snapshot();
      }
   }

   public boolean commitEdit() {
      SessionSnapshot before = this.pendingEdit;
      this.pendingEdit = null;
      if (before == null || before.equals(this.snapshot())) {
         return false;
      }
      pushHistory(this.undoHistory, before);
      this.redoHistory.clear();
      return true;
   }

   public void discardPendingEdit() {
      this.pendingEdit = null;
   }

   public void clear() {
      this.cuboidPoints.clear();
      this.prismPoints.clear();
      this.hullPoints.clear();
      this.minOffset = BlockPos.ZERO;
      this.maxOffset = BlockPos.ZERO;
      this.hullInflation = 0;
      this.translation = BlockPos.ZERO;
      this.stackVector = BlockPos.ZERO;
      this.stackRegion = OperationStackRegion.origin();
      this.rotation = Vec3.ZERO;
      this.stageMode = OperationStageMode.TRANSFORM;
      this.extend = false;
      this.selectionConfirmed = false;
      this.pointDragState = null;
      this.undoHistory.clear();
      this.redoHistory.clear();
      this.pendingEdit = null;
      this.adjustmentStarted = false;
      this.transformDrag = null;
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
      this.pointDragState = null;
   }

   private void clearAdjustments() {
      this.mode = OperationMode.MOVE;
      this.translation = BlockPos.ZERO;
      this.stackVector = BlockPos.ZERO;
      this.stackRegion = OperationStackRegion.origin();
      this.rotation = Vec3.ZERO;
      this.stageMode = OperationStageMode.TRANSFORM;
      this.adjustmentStarted = false;
      this.transformDrag = null;
   }

   private SelectionPointState selectionPoints() {
      return switch (this.selectionMode) {
         case CUBOID -> this.cuboidPoints;
         case PRISM -> this.prismPoints;
         case CONVEX_HULL -> this.hullPoints;
      };
   }

   private static BlockPos clampOffset(BlockPos offset) {
      return new BlockPos(Math.clamp(offset.getX(), -128, 128), Math.clamp(offset.getY(), -128, 128), Math.clamp(offset.getZ(), -128, 128));
   }

   private static BlockPos legacyStackVector(OperationStackRegion region) {
      return new BlockPos(
         region.max().getX() != 0 ? region.max().getX() : region.min().getX(),
         region.max().getY() != 0 ? region.max().getY() : region.min().getY(),
         region.max().getZ() != 0 ? region.max().getZ() : region.min().getZ()
      );
   }

   private TransformSnapshot transformSnapshot() {
      return new TransformSnapshot(
         this.mode, this.translation, this.stackRegion, this.rotation, this.adjustmentStarted
      );
   }

   private void restoreTransform(TransformSnapshot snapshot) {
      this.mode = snapshot.mode();
      this.translation = snapshot.translation();
      this.stackRegion = snapshot.stackRegion();
      this.stackVector = legacyStackVector(this.stackRegion);
      this.rotation = snapshot.rotation();
      this.adjustmentStarted = snapshot.adjustmentStarted();
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

   private static BlockPos addAxis(BlockPos offset, int axis, int amount) {
      return switch (axis) {
         case 0 -> offset.offset(amount, 0, 0);
         case 1 -> offset.offset(0, amount, 0);
         case 2 -> offset.offset(0, 0, amount);
         default -> offset;
      };
   }

   private SessionSnapshot snapshot() {
      return new SessionSnapshot(
         this.cuboidPoints.snapshot(),
         this.prismPoints.snapshot(),
         this.hullPoints.snapshot(),
         this.minOffset,
         this.maxOffset,
         this.cuboidMinPoint,
         this.cuboidMaxPoint,
         this.hullInflation,
         this.selectionMode,
         this.conflictMode,
         this.selectionConfirmed,
         this.mode,
         this.translation,
         this.stackRegion,
         this.rotation,
         this.stageMode,
         this.adjustmentStarted
      );
   }

   private void restore(SessionSnapshot snapshot) {
      this.clearAdjustments();
      this.cuboidPoints.restore(snapshot.cuboidPoints());
      this.prismPoints.restore(snapshot.prismPoints());
      this.hullPoints.restore(snapshot.hullPoints());
      this.minOffset = snapshot.minOffset();
      this.maxOffset = snapshot.maxOffset();
      this.cuboidMinPoint = snapshot.cuboidMinPoint();
      this.cuboidMaxPoint = snapshot.cuboidMaxPoint();
      this.hullInflation = snapshot.hullInflation();
      this.selectionMode = snapshot.selectionMode();
      this.conflictMode = snapshot.conflictMode();
      this.selectionConfirmed = snapshot.selectionConfirmed();
      this.mode = snapshot.mode();
      this.translation = snapshot.translation();
      this.stackRegion = snapshot.stackRegion();
      this.stackVector = legacyStackVector(this.stackRegion);
      this.rotation = snapshot.rotation();
      this.stageMode = snapshot.stageMode();
      this.adjustmentStarted = snapshot.adjustmentStarted();
      this.extend = false;
      this.pointDragState = null;
   }

   private void pushHistory(Deque<SessionSnapshot> history, SessionSnapshot snapshot) {
      history.addLast(snapshot);
      trimHistory(history, this.historyLimit);
   }

   private static <T> void trimHistory(Deque<T> history, int limit) {
      while (history.size() > limit) {
         history.removeFirst();
      }
   }

   private static final class SelectionPointState {
      private BlockPos first;
      private BlockPos second;
      private final List<BlockPos> extraPoints = new ArrayList<>();
      private int prismBasePointCount;
      private int selectedPointIndex = -1;

      private void clear() {
         this.first = null;
         this.second = null;
         this.extraPoints.clear();
         this.prismBasePointCount = 0;
         this.selectedPointIndex = -1;
      }

      private PointStateSnapshot snapshot() {
         return new PointStateSnapshot(this.first, this.second, List.copyOf(this.extraPoints), this.prismBasePointCount);
      }

      private void restore(PointStateSnapshot snapshot) {
         this.first = snapshot.first();
         this.second = snapshot.second();
         this.extraPoints.clear();
         this.extraPoints.addAll(snapshot.extraPoints());
         this.prismBasePointCount = snapshot.prismBasePointCount();
         this.selectedPointIndex = -1;
      }
   }

   private record PointStateSnapshot(
      BlockPos first, BlockPos second, List<BlockPos> extraPoints, int prismBasePointCount
   ) {
   }

   private record SessionSnapshot(
      PointStateSnapshot cuboidPoints,
      PointStateSnapshot prismPoints,
      PointStateSnapshot hullPoints,
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

   private record TransformSnapshot(
      OperationMode mode,
      BlockPos translation,
      OperationStackRegion stackRegion,
      Vec3 rotation,
      boolean adjustmentStarted
   ) {
      private boolean sameValues(TransformSnapshot other) {
         return this.mode == other.mode
            && this.translation.equals(other.translation)
            && this.stackRegion.equals(other.stackRegion)
            && this.rotation.equals(other.rotation);
      }
   }

   private record TransformDrag(
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int direction,
      SessionSnapshot sessionBaseline,
      TransformSnapshot baseline
   ) {
   }

   private record PointDragState(
      int pointIndex,
      SelectionPrism.GridPlane plane,
      SelectionPrism.GridLine line,
      OperationPointDragConstraint activeConstraint
   ) {
      private PointDragState rebase(BlockPos point, OperationPointDragConstraint constraint) {
         return new PointDragState(
            this.pointIndex,
            constraint == OperationPointDragConstraint.PLANE && this.plane != null
               ? this.plane.through(point)
               : this.plane,
            constraint == OperationPointDragConstraint.LINE && this.line != null
               ? this.line.through(point)
               : this.line,
            constraint
         );
      }
   }

   private record ConstrainedPoint(BlockPos target, OperationPointDragConstraint constraint) {
   }
}
