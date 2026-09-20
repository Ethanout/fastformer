package io.github.fastformer.client.operation.transform;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.CompositionBatch;
import io.github.fastformer.client.operation.preview.CompositionBudget;
import io.github.fastformer.client.operation.preview.WorkspaceSelectionBounds;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class SelectionTransformCalculator {
   private SelectionTransformCalculator() {}

   public record Result(List<ClientSelectionPart> parts, String failureKey) {
      public Result { parts = List.copyOf(parts); }
   }

   public static Result calculate(List<ClientSelectionPart> baseline, boolean common,
      AxisGizmo.Operation operation, AxisGizmo.Axis axis, int direction, int totalSteps, double rotationRadians) {
      if (totalSteps == 0) return new Result(baseline, null);
      int directedSteps = direction * totalSteps;
      // The whole selection box is the repeat unit of a stack gesture. The input quantizes
      // the drag by the same box, so both sides spend one shared value.
      boolean commonScale = common && operation == AxisGizmo.Operation.SCALE;
      AABB groupSelectionBounds = commonScale ? WorkspaceSelectionBounds.wholeBox(baseline) : null;
      // Copies stay on the base cell lattice, so a repeat gesture never moves a copy that
      // an earlier gesture placed.
      AABB groupCellBounds = commonScale ? WorkspaceSelectionBounds.baseBox(baseline) : null;
      java.util.IdentityHashMap<ClientSelectionPart, Map<BlockPos, ClientBlockSnapshot>> resolved = new java.util.IdentityHashMap<>();
      if (common && operation == AxisGizmo.Operation.ROTATE) {
         CompositionBatch batch = new CompositionBatch(CompositionBudget.INTERACTION);
         for (ClientSelectionPart part : baseline) {
            Composition<ClientBlockSnapshot> composition = batch.compose(part);
            if (composition instanceof Composition.OverBudget<?> over) {
               return new Result(List.of(), over.limit() == Composition.Limit.WORK
                  ? "fastformer.message.workspace_work_too_large"
                  : "fastformer.message.workspace_result_too_large");
            }
            resolved.put(part, ((Composition.Composed<ClientBlockSnapshot>)composition).values());
         }
      }
      OccupiedBlockBounds groupBounds = common ? resolved.values().stream()
         .filter(values -> !values.isEmpty())
         .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
         .reduce(OccupiedBlockBounds::union)
         .orElse(null) : null;
      var updates = new java.util.ArrayList<ClientSelectionPart>();
      for (ClientSelectionPart part : baseline) {
         WorkspaceTransform transform = part.transform();
         WorkspaceTransform updated = switch (operation) {
            case MOVE -> transform.withTranslation(transform.translation().add(axisVector(axis).scale(directedSteps)));
            case SCALE -> {
               if (part.selection() != null && part.selection().prism() != null) {
                  OccupiedBlockBounds sourceBounds = OccupiedBlockBounds.from(part.blocks().keySet()).orElseThrow();
                  int sourceWidth = sourceBounds.width(axis);
                  double currentScale = axisComponent(transform.scale(), axis);
                  int currentWidth = Math.max(1, (int)Math.round(sourceWidth * currentScale));
                  int targetWidth = Math.max(1, currentWidth + totalSteps);
                  double targetScale = targetWidth / (double)sourceWidth;
                  double centerShift = (targetWidth - currentWidth) * 0.5 * direction;
                  Vec3 localAxis = rotateVector(axisVector(axis), transform.rotation());
                  yield transform.withScale(axis, targetScale)
                     .withTranslation(transform.translation().add(localAxis.scale(centerShift)));
               }
               AABB wholeBounds = common ? groupSelectionBounds : WorkspaceSelectionBounds.resolve(part);
               AABB cellBounds = common ? groupCellBounds : WorkspaceSelectionBounds.resolveBase(part);
               int cellStride = Math.max(1, WorkspaceSelectionBounds.extent(cellBounds, axis));
               // One drag step repeats the whole selection and the input quantizes the drag by
               // that same whole box, so one step extends the repeat interval by the cells that
               // fit in the whole. A gesture without whole-group repeats keeps its block-valued
               // step, because the input did not quantize it by a group box.
               int cellsPerStep = io.github.fastformer.client.input.RepeatStrideSemantics
                  .repeatsWholeGroup(operation, baseline)
                  ? WorkspaceSelectionBounds.wholeStepCells(wholeBounds, cellBounds, axis)
                  : 1;
               int groupDelta = (int)Math.clamp(
                  (long)totalSteps * cellsPerStep,
                  (long)-OperationStackRegion.ENDPOINT_LIMIT,
                  (long)OperationStackRegion.ENDPOINT_LIMIT
               );
               WorkspaceTransform axisStride = transform.withRepeatStride(axis, cellStride);
               yield axisStride.withRepeats(
                  transform.repeats().withAxisEndpoint(axis, direction, groupDelta),
                  axisStride.repeatStride()
               );
            }
            case ROTATE -> {
               double radians = Double.isFinite(rotationRadians)
                  ? rotationRadians
                  : totalSteps * Math.PI * 2.0 / 1024.0;
               Vec3 rotation = transform.rotation().add(axisVector(axis).scale(radians));
               Vec3 translation = transform.translation();
               if (common && groupBounds != null) {
                  Map<BlockPos, ClientBlockSnapshot> current = resolved.getOrDefault(part, Map.of());
                  if (!current.isEmpty()) {
                     Vec3 center = OccupiedBlockBounds.from(current.keySet()).orElseThrow().center();
                     Vec3 revolved = rotateAround(center, groupBounds.center(), axis, radians);
                     translation = translation.add(revolved.subtract(center));
                  }
               }
               yield new WorkspaceTransform(
                  translation, rotation, transform.repeats(), transform.repeatStride(), transform.scale()
               );
            }
         };
         updates.add(part.withTransform(updated));
      }
      return new Result(updates, null);
   }

   private static Vec3 axisVector(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
   }

   private static Vec3 rotateAround(Vec3 point, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      Vec3 value = point.subtract(pivot);
      double sin = Math.sin(radians);
      double cos = Math.cos(radians);
      Vec3 rotated = switch (axis) {
         case X -> new Vec3(value.x, value.y * cos - value.z * sin, value.y * sin + value.z * cos);
         case Y -> new Vec3(value.x * cos + value.z * sin, value.y, -value.x * sin + value.z * cos);
         case Z -> new Vec3(value.x * cos - value.y * sin, value.x * sin + value.y * cos, value.z);
      };
      return rotated.add(pivot);
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static Vec3 rotateVector(Vec3 value, Vec3 rotation) {
      double xSin = Math.sin(rotation.x);
      double xCos = Math.cos(rotation.x);
      value = new Vec3(value.x, value.y * xCos - value.z * xSin, value.y * xSin + value.z * xCos);
      double ySin = Math.sin(rotation.y);
      double yCos = Math.cos(rotation.y);
      value = new Vec3(value.x * yCos + value.z * ySin, value.y, -value.x * ySin + value.z * yCos);
      double zSin = Math.sin(rotation.z);
      double zCos = Math.cos(rotation.z);
      return new Vec3(value.x * zCos - value.y * zSin, value.x * zSin + value.y * zCos, value.z);
   }
}
