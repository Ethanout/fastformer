package io.github.fastformer.common.selection.session;

import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.common.selection.OperationStageMode;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.core.math.Axis;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Owns transform values and the baseline of one in-progress transform. */
final class OperationTransformState {
   OperationMode mode = OperationMode.MOVE;
   BlockPos translation = BlockPos.ZERO;
   BlockPos stackVector = BlockPos.ZERO;
   OperationStackRegion stackRegion = OperationStackRegion.origin();
   Vec3 rotation = Vec3.ZERO;
   OperationStageMode stageMode = OperationStageMode.TRANSFORM;
   boolean adjustmentStarted;
   private Drag drag;

   boolean active() { return drag != null; }

   boolean begin(AxisGizmo.Operation operation, Axis axis, int direction, boolean ready) {
      if (!ready || operation == null || axis == null || stageMode != OperationStageMode.TRANSFORM) return false;
      int normalized = Integer.compare(direction, 0);
      if (operation != AxisGizmo.Operation.ROTATE && normalized == 0) return false;
      drag = new Drag(operation, axis, normalized, snapshot());
      return true;
   }

   boolean update(int totalSteps) {
      Drag current = drag;
      if (current == null) return false;
      restoreValues(current.baseline());
      int directed = current.operation() == AxisGizmo.Operation.ROTATE && current.direction() == 0
         ? totalSteps : current.direction() * Math.max(0, totalSteps);
      switch (current.operation()) {
         case MOVE -> {
            if (directed != 0) mode = OperationMode.MOVE;
            translation = clampOffset(addAxis(current.baseline().translation(), current.axis().ordinal(), directed));
         }
         case SCALE -> {
            if (directed != 0) mode = OperationMode.STACK;
            stackRegion = current.baseline().stackRegion().repeat(current.axis(), current.direction(), Math.max(0, totalSteps));
            stackVector = legacyStackVector(stackRegion);
         }
         case ROTATE -> {
            double radians = directed * Math.PI * 2.0 / 1024.0;
            rotation = switch (current.axis()) {
               case X -> current.baseline().rotation().add(radians, 0.0, 0.0);
               case Y -> current.baseline().rotation().add(0.0, radians, 0.0);
               case Z -> current.baseline().rotation().add(0.0, 0.0, radians);
            };
         }
      }
      adjustmentStarted = current.baseline().adjustmentStarted() || !snapshot().sameValues(current.baseline());
      return true;
   }

   boolean finish() {
      Drag current = drag;
      drag = null;
      if (current == null) return false;
      if (snapshot().sameValues(current.baseline())) {
         restoreValues(current.baseline());
         return false;
      }
      adjustmentStarted = true;
      return true;
   }

   void scroll(BlockPos axisStep, boolean stack, boolean confirmed) {
      if (!confirmed || stageMode != OperationStageMode.TRANSFORM) return;
      mode = stack ? OperationMode.STACK : OperationMode.MOVE;
      if (stack) stackVector = clampOffset(stackVector.offset(axisStep));
      else translation = clampOffset(translation.offset(axisStep));
   }

   boolean adjust(AxisGizmo.Operation operation, Axis axis, int steps, boolean confirmed) {
      if (!confirmed || steps == 0 || operation == null || axis == null || stageMode != OperationStageMode.TRANSFORM) return false;
      if (operation != AxisGizmo.Operation.ROTATE) {
         scroll(addAxis(BlockPos.ZERO, axis.ordinal(), steps), operation == AxisGizmo.Operation.SCALE, true);
      } else {
         double radians = steps * Math.PI * 2.0 / 1024.0;
         rotation = switch (axis) {
            case X -> rotation.add(radians, 0.0, 0.0);
            case Y -> rotation.add(0.0, radians, 0.0);
            case Z -> rotation.add(0.0, 0.0, radians);
         };
      }
      return true;
   }

   private Snapshot snapshot() { return new Snapshot(mode, translation, stackRegion, rotation, adjustmentStarted); }
   private void restoreValues(Snapshot snapshot) {
      mode = snapshot.mode();
      translation = snapshot.translation();
      stackRegion = snapshot.stackRegion();
      stackVector = legacyStackVector(stackRegion);
      rotation = snapshot.rotation();
      adjustmentStarted = snapshot.adjustmentStarted();
   }

   static BlockPos legacyStackVector(OperationStackRegion region) {
      return new BlockPos(
         region.max().getX() != 0 ? region.max().getX() : region.min().getX(),
         region.max().getY() != 0 ? region.max().getY() : region.min().getY(),
         region.max().getZ() != 0 ? region.max().getZ() : region.min().getZ());
   }
   static BlockPos clampOffset(BlockPos offset) {
      return new BlockPos(Math.clamp(offset.getX(), -128, 128), Math.clamp(offset.getY(), -128, 128), Math.clamp(offset.getZ(), -128, 128));
   }
   static BlockPos addAxis(BlockPos offset, int axis, int amount) {
      return switch (axis) {
         case 0 -> offset.offset(amount, 0, 0); case 1 -> offset.offset(0, amount, 0);
         case 2 -> offset.offset(0, 0, amount); default -> offset;
      };
   }

   void clear() {
      this.mode = OperationMode.MOVE;
      this.translation = BlockPos.ZERO;
      this.stackVector = BlockPos.ZERO;
      this.stackRegion = OperationStackRegion.origin();
      this.rotation = Vec3.ZERO;
      this.stageMode = OperationStageMode.TRANSFORM;
      this.adjustmentStarted = false;
      this.drag = null;
   }

   private record Snapshot(OperationMode mode, BlockPos translation, OperationStackRegion stackRegion,
      Vec3 rotation, boolean adjustmentStarted) {
      boolean sameValues(Snapshot other) {
         return mode == other.mode && translation.equals(other.translation)
            && stackRegion.equals(other.stackRegion) && rotation.equals(other.rotation);
      }
   }
   private record Drag(AxisGizmo.Operation operation, Axis axis, int direction, Snapshot baseline) {}
}
