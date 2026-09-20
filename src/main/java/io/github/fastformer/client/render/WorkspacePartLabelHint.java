package io.github.fastformer.client.render;

import io.github.fastformer.client.input.OperationInteractionIntent;
import net.minecraft.network.chat.Component;

/**
 * Chooses the in-world part label from the final pointer intent.
 * Ctrl toggle text is only used for the Part selection branch.
 */
public final class WorkspacePartLabelHint {
   public static final String ID_KEY = "fastformer.operation.part_label.id";
   public static final String HOVERED_KEY = "fastformer.operation.part_label.hovered";
   public static final String SELECTED_KEY = "fastformer.operation.part_label.selected";
   public static final String DESELECT_KEY = "fastformer.operation.part_label.deselect";
   public static final String APPEND_KEY = "fastformer.operation.part_label.append";
   public static final String ACTION_KEY = "fastformer.operation.part_label.action";

   private WorkspacePartLabelHint() {
   }

   public static Component text(
      int partId,
      boolean selected,
      boolean workspaceLocked,
      boolean controlHeld,
      OperationInteractionIntent intent
   ) {
      if (!WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         return selected ? selected(partId) : id(partId);
      }
      if (intent instanceof OperationInteractionIntent.Gizmo gizmo && ownsGizmo(partId, gizmo)) {
         return action(partId, gizmo.requireHoverText());
      }
      if (intent instanceof OperationInteractionIntent.Part part
         && part.partId() == partId
         && controlHeld) {
         return selected ? deselect(partId) : append(partId);
      }
      if (hoveringPart(partId, intent)) {
         return hovered(partId);
      }
      return selected ? selected(partId) : id(partId);
   }

   private static boolean ownsGizmo(int partId, OperationInteractionIntent.Gizmo gizmo) {
      return !gizmo.common() && gizmo.partId() == partId;
   }

   private static boolean hoveringPart(int partId, OperationInteractionIntent intent) {
      if (intent instanceof OperationInteractionIntent.Part part) {
         return part.partId() == partId;
      }
      if (intent instanceof OperationInteractionIntent.Face face) {
         return face.partId() == partId;
      }
      return false;
   }

   private static Component id(int partId) {
      return Component.translatable(ID_KEY, partId);
   }

   private static Component hovered(int partId) {
      return Component.translatable(HOVERED_KEY, partId);
   }

   private static Component selected(int partId) {
      return Component.translatable(SELECTED_KEY, partId);
   }

   private static Component deselect(int partId) {
      return Component.translatable(DESELECT_KEY, partId);
   }

   private static Component append(int partId) {
      return Component.translatable(APPEND_KEY, partId);
   }

   private static Component action(int partId, Component operation) {
      return Component.translatable(ACTION_KEY, partId, operation);
   }
}
