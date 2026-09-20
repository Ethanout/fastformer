package io.github.fastformer.client.input;

import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.InteractionComponents;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.interaction.InteractionVisibility;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;

/** Values sampled together at the physical press boundary, before selection changes. */
public record SelectionPointerPress(
   OperationInteractionIntent target, int button, boolean control, int shortPressSteps,
   UUID owner, Map<Integer, SelectionInteractionScene.Part> targets,
   InteractionObject object, long pressedAtNanos
) {
   public SelectionPointerPress {
      Objects.requireNonNull(owner, "owner");
      targets = Map.copyOf(targets);
      if (!supportedTarget(target) || button < 0 || button > 2) {
         throw new IllegalArgumentException("Selection press requires a target and a supported mouse button");
      }
      Objects.requireNonNull(object, "object");
      if (!owner.equals(object.id().session()) || object.component(InteractionComponents.PRESS_BINDING)
         .flatMap(binding -> binding.resolve(target)).isEmpty()) {
         throw new IllegalArgumentException("Selection target requires an action owned by its session");
      }
   }

   public InteractionPressBinding.Action action() {
      return this.object.require(InteractionComponents.PRESS_BINDING).resolve(this.target).orElseThrow();
   }

   public static Optional<SelectionPointerPress> capture(
      OperationInteractionIntent target, int button, boolean control, int shortPressSteps,
      SelectionInteractionScene scene, ClientOperationWorkspace workspace
   ) {
      return capture(target, button, control, shortPressSteps, scene, workspace, System.nanoTime());
   }

   public static Optional<SelectionPointerPress> capture(
      OperationInteractionIntent target, int button, boolean control, int shortPressSteps,
      SelectionInteractionScene scene, ClientOperationWorkspace workspace, long pressedAtNanos
   ) {
      if (!supportedTarget(target) || button < 0 || button > 2) return Optional.empty();
      var object = scene.targetObject(target);
      if (object == null || object.component(InteractionComponents.PRESS_BINDING)
         .flatMap(binding -> binding.resolve(target)).isEmpty()) return Optional.empty();
      Set<Integer> ids = switch (target) {
         case OperationInteractionIntent.Gizmo gizmo -> gizmo.common()
            ? scene.groupGizmo() == null ? Set.of() : scene.groupGizmo().require(InteractionComponents.GROUP_GIZMO).members().keySet()
            : Set.of(gizmo.partId());
         case OperationInteractionIntent.Face face -> Set.of(face.partId());
         case OperationInteractionIntent.Part part -> Set.of(part.partId());
         default -> Set.of();
      };
      Map<Integer, SelectionInteractionScene.Part> targets = new LinkedHashMap<>();
      for (int id : ids) {
         var part = scene.parts().get(id);
         if (part == null) return Optional.empty();
         targets.put(id, part);
      }
      var press = new SelectionPointerPress(target, button, control, shortPressSteps, scene.owner(), targets, object, pressedAtNanos);
      return press.matches(scene.owner(), workspace) ? Optional.of(press) : Optional.empty();
   }

   public boolean matches(UUID currentOwner, ClientOperationWorkspace workspace) {
      if (!this.owner.equals(currentOwner) || this.targets.isEmpty() || workspace.locked()) return false;
      boolean selected = this.targets.keySet().stream().allMatch(workspace.selectedIds()::contains);
      if (!InteractionVisibility.isVisible(this.object, selected)) return false;
      if (this.target instanceof OperationInteractionIntent.Gizmo gizmo && gizmo.common()
         && !workspace.selectedIds().equals(this.targets.keySet())) return false;
      for (var entry : this.targets.entrySet()) {
         var current = workspace.part(entry.getKey()).orElse(null);
         var captured = entry.getValue();
         if (current == null || current != captured.source()
            || workspace.interactionId(entry.getKey()) != captured.identity()) return false;
      }
      return true;
   }

   public boolean matches(SelectionInteractionScene scene, ClientOperationWorkspace workspace) {
      return scene.object(this.object.id()) == this.object && matches(scene.owner(), workspace);
   }

   private static boolean supportedTarget(OperationInteractionIntent target) {
      return target instanceof OperationInteractionIntent.Gizmo
         || target instanceof OperationInteractionIntent.Face
         || target instanceof OperationInteractionIntent.Part part && part.partId() > 0;
   }
}
