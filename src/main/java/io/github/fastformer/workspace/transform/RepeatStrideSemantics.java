package io.github.fastformer.workspace.transform;

import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.preview.WorkspaceSelectionBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * Resolves the handle travel that adds one repeat group during a stack (SCALE)
 * drag.
 *
 * <p>Placed copies do not change the repeat unit. A single part keeps its saved
 * stride. A common handle uses the base group envelope, including selected air.
 */
public final class RepeatStrideSemantics {
   private RepeatStrideSemantics() {
   }

   /**
    * True when this gesture repeats whole groups. A prism part changes its own
    * size instead, so it never repeats.
    */
   public static boolean repeatsWholeGroup(AxisGizmo.Operation operation, List<ClientSelectionPart> baseline) {
      return operation == AxisGizmo.Operation.SCALE
         && baseline != null
         && baseline.stream().noneMatch(RepeatStrideSemantics::isPrism);
   }

   /**
    * Stride for one repeat step, in blocks along the axis.
    *
    * <p>Returns 1 when the gesture does not repeat whole groups: another
    * operation, a prism part, or a missing handle target.
    */
   public static int stride(
      AxisGizmo.Operation operation,
      boolean common,
      int partId,
      List<ClientSelectionPart> baseline,
      AxisGizmo.Axis axis
   ) {
      if (axis == null || !repeatsWholeGroup(operation, baseline)) {
         return 1;
      }
      if (common) {
         return stride(WorkspaceSelectionBounds.baseBox(baseline), axis);
      }
      return baseline.stream()
         .filter(part -> part.id() == partId)
         .findFirst()
         .map(part -> cellStride(part, axis))
         .orElse(1);
   }

   /** Uses the stored lattice when one exists, otherwise the original local envelope. */
   public static int cellStride(ClientSelectionPart part, AxisGizmo.Axis axis) {
      if (part == null || axis == null) return 1;
      int saved = switch (axis) {
         case X -> part.transform().repeatStride().getX();
         case Y -> part.transform().repeatStride().getY();
         case Z -> part.transform().repeatStride().getZ();
      };
      return saved == 0 ? stride(WorkspaceSelectionBounds.resolveLocal(part, false), axis)
         : (int)Math.min(Integer.MAX_VALUE, Math.abs((long)saved));
   }

   /** Envelope extent along the axis. Never below one block. */
   public static int stride(AABB wholeBox, AxisGizmo.Axis axis) {
      return Math.max(1, WorkspaceSelectionBounds.extent(wholeBox, axis));
   }

   private static boolean isPrism(ClientSelectionPart part) {
      return part.selection() != null && part.selection().prism() != null;
   }
}
