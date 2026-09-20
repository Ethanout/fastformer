package io.github.fastformer.client.interaction;

import io.github.fastformer.client.input.OperationInteractionIntent;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** Typed presentation policies. They read resolved capabilities and never perform an action. */
public enum InteractionTooltip {
   SELECTION,
   FRAME,
   GIZMO;

   public Optional<Component> summary(OperationInteractionIntent target) {
      if (this == GIZMO && target instanceof OperationInteractionIntent.Gizmo gizmo) {
         return Optional.of(gizmo.hit().handle().hoverText());
      }
      if (this == FRAME && target instanceof OperationInteractionIntent.Face face) {
         return Optional.of(Component.translatable(face.adjustable()
            ? "fastformer.operation.face_drag_hint" : "fastformer.operation.selection_click_hint"));
      }
      if ((this == SELECTION || this == FRAME) && target instanceof OperationInteractionIntent.Part) {
         return Optional.of(Component.translatable("fastformer.operation.selection_click_hint"));
      }
      return Optional.empty();
   }

   public List<Component> lines(OperationInteractionIntent target) {
      if (this == FRAME && target instanceof OperationInteractionIntent.Face face && face.adjustable()) {
         return List.of(Component.translatable("fastformer.hud.selection.push"),
            Component.translatable("fastformer.hud.selection.pull"));
      }
      return summary(target).map(List::of).orElseGet(List::of);
   }

   public static Optional<Component> summary(InteractionObject object, OperationInteractionIntent target) {
      return object.component(InteractionComponents.TOOLTIP).flatMap(policy -> policy.summary(target));
   }
}
