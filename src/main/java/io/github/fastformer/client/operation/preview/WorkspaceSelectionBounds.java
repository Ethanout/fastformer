package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.operation.transform.VoxelRotation;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Computes the transformed selection range independently of occupied blocks. */
public final class WorkspaceSelectionBounds {
   private WorkspaceSelectionBounds() {
   }

   public static AABB resolve(ClientSelectionPart part) {
      return resolve(part, true);
   }

   /**
    * Union of the transformed selection boxes of every part. Placed copies and the air
    * inside a selection envelope are part of this box.
    */
   public static AABB wholeBox(List<ClientSelectionPart> parts) {
      return unionBoxes(parts, true);
   }

   /** Union of the base selection boxes. Copies placed by an earlier stack stay outside it. */
   public static AABB baseBox(List<ClientSelectionPart> parts) {
      return unionBoxes(parts, false);
   }

   /**
    * Repeat cells that one whole-selection step covers along the axis.
    *
    * <p>A repeat gesture keeps the stride field on the base cell extent, so one step that
    * repeats the whole selection must extend the repeat interval by this many cells. The
    * result is never below one.
    */
   public static int wholeStepCells(AABB wholeBounds, AABB baseBounds, AxisGizmo.Axis axis) {
      int base = Math.max(1, extent(baseBounds, axis));
      return Math.max(1, extent(wholeBounds, axis) / base);
   }

   private static AABB unionBoxes(List<ClientSelectionPart> parts, boolean includeRepeats) {
      if (parts == null) {
         return null;
      }
      AABB union = null;
      for (ClientSelectionPart part : parts) {
         AABB box = includeRepeats ? resolve(part) : resolveBase(part);
         if (box != null) {
            union = union(union, box);
         }
      }
      return union;
   }

   /**
    * Transformed selection box without repetition envelopes.
    *
    * <p>Stack strides must use this box. The repeat count grows the resolved envelope, so
    * deriving the next stride from {@link #resolve(ClientSelectionPart)} would move the
    * copies already placed.
    */
   public static AABB resolveBase(ClientSelectionPart part) {
      return resolve(part, false);
   }

   private static AABB resolve(ClientSelectionPart part, boolean includeRepeats) {
      if (part == null) {
         return null;
      }
      WorkspaceTransform transform = includeRepeats
         ? part.transform()
         : part.transform().withoutRepeats();
      if (part.selection() != null && part.axisAlignedCuboid()
         && transform.rotation().equals(Vec3.ZERO)) {
         return axisAligned(part.selection().bounds(), transform, geometryFrame(part, transform));
      }
      if (part.selection() != null && part.selection().mode() == OperationSelectionMode.CUBOID) {
         return rotatedCuboid(part.selection().bounds(), transform, geometryFrame(part, transform));
      }
      // Non-cuboid selections can be too large or non-orthogonal to resolve
      // into renderable blocks. Keep a conservative transformed envelope for
      // the outline and Gizmo instead of dropping the interaction surface.
      if (part.selection() != null && transform.hasEffect()) {
         return rotatedCuboid(part.selection().bounds(), transform, geometryFrame(part, transform));
      }
      Composition<ClientBlockSnapshot> composition = WorkspacePreviewComposer.composeSnapshots(
         part.blocks(), transform, CompositionBudget.INTERACTION
      );
      if (!(composition instanceof Composition.Composed<ClientBlockSnapshot> composed)) {
         return null;
      }
      OccupiedBlockBounds occupied = OccupiedBlockBounds.from(composed.values().keySet()).orElse(null);
      return occupied == null ? null : occupied.aabb();
   }

   /**
    * The frame the voxel pipeline uses for this part, or null when the part is outside the
    * render budget. Without a frame the caller keeps the conservative single-pivot envelope.
    */
   private static WorkspacePreviewComposer.GeometryFrame geometryFrame(
      ClientSelectionPart part, WorkspaceTransform transform
   ) {
      if (!WorkspacePreviewComposer.frameChangesGeometry(transform)) {
         return null;
      }
      return transform == part.transform()
         ? WorkspacePreviewComposer.frameForPart(part)
         : WorkspacePreviewComposer.geometryFrame(part.blocks(), transform);
   }

   /**
    * Envelope of one raw selection box under a transform frame.
    *
    * <p>Kept package-private so the frame contract can be tested without a full part.
    */
   static AABB transformedBox(
      AABB source, WorkspaceTransform transform, WorkspacePreviewComposer.GeometryFrame frame
   ) {
      return transform.rotation().equals(Vec3.ZERO)
         ? axisAligned(source, transform, frame)
         : rotatedCuboid(source, transform, frame);
   }

   public static AABB union(AABB first, AABB second) {
      if (first == null) return second;
      if (second == null) return first;
      return new AABB(
         Math.min(first.minX, second.minX), Math.min(first.minY, second.minY), Math.min(first.minZ, second.minZ),
         Math.max(first.maxX, second.maxX), Math.max(first.maxY, second.maxY), Math.max(first.maxZ, second.maxZ)
      );
   }

   public static int extent(AABB bounds, AxisGizmo.Axis axis) {
      if (bounds == null) return 0;
      double size = switch (axis) {
         case X -> bounds.getXsize();
         case Y -> bounds.getYsize();
         case Z -> bounds.getZsize();
      };
      return Math.max(1, (int)Math.ceil(size - 1.0E-7));
   }

   /**
    * The pre-rotation envelope of the selection box.
    *
    * <p>With a frame, the box takes the scale anchor and the repeat cell stride the voxel
    * pipeline used, so a scaled or repeated envelope cannot drift off its blocks. Without a
    * frame it keeps the selection centre and the selection-based stride.
    */
   private static AABB axisAligned(
      AABB source, WorkspaceTransform transform, WorkspacePreviewComposer.GeometryFrame frame
   ) {
      double width = scaledExtent(source.getXsize(), transform.scale().x);
      double height = scaledExtent(source.getYsize(), transform.scale().y);
      double depth = scaledExtent(source.getZsize(), transform.scale().z);
      Vec3 center = frame != null && frame.scaleAnchor() != null ? frame.scaleAnchor() : source.getCenter();
      double minX = center.x - width * 0.5;
      double minY = center.y - height * 0.5;
      double minZ = center.z - depth * 0.5;
      double maxX = center.x + width * 0.5;
      double maxY = center.y + height * 0.5;
      double maxZ = center.z + depth * 0.5;
      var repeats = transform.repeats();
      BlockPos stride = frame != null && frame.repeatStrideCells() != null
         ? frame.repeatStrideCells()
         : transform.repeatStride();
      double strideX = stride.getX() == 0 ? width : Math.abs((double)stride.getX());
      double strideY = stride.getY() == 0 ? height : Math.abs((double)stride.getY());
      double strideZ = stride.getZ() == 0 ? depth : Math.abs((double)stride.getZ());
      minX += Math.min(0.0, repeats.min().getX() * strideX);
      maxX += Math.max(0.0, repeats.max().getX() * strideX);
      minY += Math.min(0.0, repeats.min().getY() * strideY);
      maxY += Math.max(0.0, repeats.max().getY() * strideY);
      minZ += Math.min(0.0, repeats.min().getZ() * strideZ);
      maxZ += Math.max(0.0, repeats.max().getZ() * strideZ);
      Vec3 translation = transform.translation();
      return new AABB(minX + translation.x, minY + translation.y, minZ + translation.z,
         maxX + translation.x, maxY + translation.y, maxZ + translation.z);
   }

   private static double scaledExtent(double extent, double scale) {
      if (!Double.isFinite(scale) || scale <= 0.0) return extent;
      return Math.max(1.0, Math.round(extent * scale));
   }

   /**
    * Transforms the eight selection-box corners.
    *
    * <p>With a frame, every corner replays the exact rotation steps the voxel pipeline used,
    * including the per-axis pivot. That is what keeps the outer frame and the Gizmo on the
    * rotated blocks when the selection holds air or the rotation uses more than one axis.
    * Without a frame, all axes rotate about the single box centre.
    */
   private static AABB rotatedCuboid(
      AABB source, WorkspaceTransform transform, WorkspacePreviewComposer.GeometryFrame frame
   ) {
      AABB base = axisAligned(source, transform.withRotation(Vec3.ZERO).withTranslation(Vec3.ZERO), frame);
      List<VoxelRotation.RotationStep> steps = frame == null ? List.of() : frame.rotationSteps();
      if (steps.isEmpty()) {
         Vec3 pivot = base.getCenter();
         List<VoxelRotation.RotationStep> fallback = new java.util.ArrayList<>(3);
         for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
            double radians = axisComponent(transform.rotation(), axis);
            if (Math.abs(radians) > VoxelRotation.POSITION_EPSILON) {
               fallback.add(new VoxelRotation.RotationStep(axis, radians, pivot));
            }
         }
         steps = fallback;
      }
      AABB result = null;
      for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
         Vec3 point = new Vec3(
            x == 0 ? base.minX : base.maxX,
            y == 0 ? base.minY : base.maxY,
            z == 0 ? base.minZ : base.maxZ
         );
         for (VoxelRotation.RotationStep step : steps) {
            point = VoxelRotation.rotatePoint(point, step.pivot(), step.axis(), step.radians());
         }
         point = point.add(transform.translation());
         result = union(result, new AABB(point, point));
      }
      return result;
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }
}
