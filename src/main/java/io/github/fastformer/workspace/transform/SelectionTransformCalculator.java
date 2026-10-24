package io.github.fastformer.workspace.transform;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.Composition;
import io.github.fastformer.workspace.preview.CompositionBatch;
import io.github.fastformer.workspace.preview.CompositionBudget;
import io.github.fastformer.workspace.preview.WorkspaceSelectionBounds;
import io.github.fastformer.workspace.selection.OccupiedBlockBounds;
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
      return calculate(baseline, common, operation, axis, direction, totalSteps, rotationRadians,
         WorkspaceRotation.unitAxis(axis), null);
   }

   public static Result calculate(List<ClientSelectionPart> baseline, boolean common,
      AxisGizmo.Operation operation, AxisGizmo.Axis axis, int direction, int totalSteps, double rotationRadians,
      Vec3 rotationAxis, Vec3 rotationPivot) {
      return calculate(baseline, common, operation, axis, direction, totalSteps, rotationRadians, rotationAxis, rotationPivot, false);
   }

   /** Uses the handle's stack calculation, with a scroll boundary that can cross the source cell. */
   public static Result scrollStack(List<ClientSelectionPart> baseline, AxisGizmo.Axis axis, int side, int steps) {
      return calculate(baseline, baseline.size() > 1, AxisGizmo.Operation.SCALE, axis, side, steps, Double.NaN,
         WorkspaceRotation.unitAxis(axis), null, true);
   }

   private static Result calculate(List<ClientSelectionPart> baseline, boolean common,
      AxisGizmo.Operation operation, AxisGizmo.Axis axis, int direction, int totalSteps, double rotationRadians,
      Vec3 rotationAxis, Vec3 rotationPivot, boolean crossStackOrigin) {
      if (totalSteps == 0) return new Result(baseline, null);
      int directedSteps = direction * totalSteps;
      double radians = Double.isFinite(rotationRadians) ? rotationRadians : totalSteps * Math.PI * 2.0 / 1024.0;
      boolean commonScale = common && operation == AxisGizmo.Operation.SCALE;
      // Copies stay on the base cell lattice, so a repeat gesture never moves a copy that
      // an earlier gesture placed.
      AABB groupCellBounds = commonScale ? WorkspaceSelectionBounds.baseBox(baseline) : null;
      java.util.IdentityHashMap<ClientSelectionPart, Map<BlockPos, ClientBlockSnapshot>> resolved = new java.util.IdentityHashMap<>();
      if (operation == AxisGizmo.Operation.ROTATE) {
         CompositionBatch batch = new CompositionBatch(CompositionBudget.INTERACTION);
         for (ClientSelectionPart part : baseline) {
            Composition<ClientBlockSnapshot> composition = batch.compose(part);
            if (composition instanceof Composition.OverBudget<?> over) {
               return failure(over);
            }
            resolved.put(part, ((Composition.Composed<ClientBlockSnapshot>)composition).values());
         }
      }
      OccupiedBlockBounds groupBounds = common ? resolved.values().stream()
         .filter(values -> !values.isEmpty())
         .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
         .reduce(OccupiedBlockBounds::union)
         .orElse(null) : null;
      CompositionBatch rotatedBatch = new CompositionBatch(CompositionBudget.INTERACTION);
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
                  Vec3 localAxis = WorkspaceRotation.localAxis(transform, axis);
                  yield transform.withScale(axis, targetScale)
                     .withTranslation(transform.translation().add(localAxis.scale(centerShift)));
               }
               int cellStride = common ? RepeatStrideSemantics.stride(groupCellBounds, axis)
                  : RepeatStrideSemantics.cellStride(part, axis);
               // Each step adjusts one saved repeat cell, including after a release.
               int groupDelta = (int)Math.clamp(
                  (long)totalSteps,
                  (long)-OperationStackRegion.ENDPOINT_LIMIT,
                  (long)OperationStackRegion.ENDPOINT_LIMIT
               );
               WorkspaceTransform axisStride = transform.withRepeatStride(axis, cellStride);
               yield axisStride.withRepeats(
                  crossStackOrigin ? transform.repeats().stepAcrossOrigin(axis, directedSteps)
                     : transform.repeats().withAxisEndpoint(axis, direction, groupDelta),
                  axisStride.repeatStride()
               );
            }
            case ROTATE -> {
               Vec3 rotation = WorkspaceRotation.rotateWorld(transform, rotationAxis, radians);
               yield transform.withRotation(rotation);
            }
         };
         if (operation == AxisGizmo.Operation.ROTATE) {
            Composition<ClientBlockSnapshot> composition = rotatedBatch.compose(part.withTransform(updated));
            if (composition instanceof Composition.OverBudget<?> over) {
               return failure(over);
            }
            var before = OccupiedBlockBounds.from(resolved.getOrDefault(part, Map.of()).keySet()).orElse(null);
            var after = OccupiedBlockBounds.from(((Composition.Composed<ClientBlockSnapshot>)composition).values().keySet()).orElse(null);
            if (before != null && after != null) {
               Vec3 pivot = rotationPivot;
               if (pivot == null) pivot = common && groupBounds != null ? groupBounds.center() : before.center();
               Vec3 targetCenter = WorkspaceRotation.rotateAroundAxis(before.center().subtract(pivot), rotationAxis, radians).add(pivot);
               updated = updated.withTranslation(updated.translation().add(targetCenter.subtract(after.center())));
            }
         }
         updates.add(part.withTransform(updated));
      }
      return new Result(updates, null);
   }

   private static Result failure(Composition.OverBudget<?> over) {
      return new Result(List.of(), over.limit() == Composition.Limit.WORK
         ? "fastformer.message.workspace_work_too_large"
         : "fastformer.message.workspace_result_too_large");
   }

   private static Vec3 axisVector(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

}
