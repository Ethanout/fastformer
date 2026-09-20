package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.preview.WorkspaceSelectionBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.world.phys.AABB;

/**
 * Resolves the handle travel that adds one repeat group during a stack (SCALE)
 * drag.
 *
 * <p>One drag step repeats the whole current selection, so the stride is the
 * extent of the whole selection box along the handle axis. That box holds the
 * copies that earlier stacks placed and the air inside the selection envelope.
 * The controller also reads {@link WorkspaceSelectionBounds}. This keeps the
 * input distance tied to the selection envelope rather than occupied blocks.
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
         return stride(wholeSelectionBox(baseline), axis);
      }
      return baseline.stream()
         .filter(part -> part.id() == partId)
         .findFirst()
         .map(part -> stride(WorkspaceSelectionBounds.resolve(part), axis))
         .orElse(1);
   }

   /** Union of the whole selection boxes, placed copies included. */
   public static AABB wholeSelectionBox(List<ClientSelectionPart> baseline) {
      if (baseline == null) {
         return null;
      }
      AABB union = null;
      for (ClientSelectionPart part : baseline) {
         AABB box = WorkspaceSelectionBounds.resolve(part);
         if (box != null) {
            union = WorkspaceSelectionBounds.union(union, box);
         }
      }
      return union;
   }

   /** Whole-selection extent along the axis. Never below one block. */
   public static int stride(AABB wholeBox, AxisGizmo.Axis axis) {
      return Math.max(1, WorkspaceSelectionBounds.extent(wholeBox, axis));
   }

   private static boolean isPrism(ClientSelectionPart part) {
      return part.selection() != null && part.selection().prism() != null;
   }
}
