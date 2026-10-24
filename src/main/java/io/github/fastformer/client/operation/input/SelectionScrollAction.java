package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.transform.WorkspaceRotation;

/** Freezes the gesture and target identities at the physical scroll callback. */
public record SelectionScrollAction(UUID owner, Map<Integer, Long> targets, Gesture gesture, long timestampNanos) {
   public sealed interface Gesture permits Move, Stack { }
   public record Move(BlockPos offset) implements Gesture {
      public Move { offset = offset.immutable(); }
   }
   public record Stack(AxisGizmo.Axis axis, int side, int steps) implements Gesture {
      public Stack {
         java.util.Objects.requireNonNull(axis);
         if (Math.abs(side) != 1 || Math.abs(steps) != 1) throw new IllegalArgumentException("Stack side and steps must be -1 or 1");
      }
   }

   public static Stack stackAlongView(WorkspaceTransform transform, Vec3 view, int steps) {
      AxisGizmo.Axis closest = AxisGizmo.Axis.X;
      double projection = view.dot(WorkspaceRotation.localAxis(transform, closest));
      for (var axis : AxisGizmo.Axis.values()) {
         double candidate = view.dot(WorkspaceRotation.localAxis(transform, axis));
         if (Math.abs(candidate) > Math.abs(projection)) {
            closest = axis;
            projection = candidate;
         }
      }
      return new Stack(closest, projection < 0 ? -1 : 1, steps);
   }

   public SelectionScrollAction {
      targets = Map.copyOf(targets);
      java.util.Objects.requireNonNull(gesture);
   }

   public static Optional<SelectionScrollAction> capture(UUID owner, ClientOperationWorkspace workspace, Gesture gesture) {
      if (workspace.locked() || workspace.editing() || workspace.selectedIds().isEmpty()
         || gesture instanceof Move move && move.offset().equals(BlockPos.ZERO)) return Optional.empty();
      var targets = workspace.selectedIds().stream().collect(Collectors.toMap(id -> id, workspace::interactionId));
      return Optional.of(new SelectionScrollAction(owner, targets, gesture, System.nanoTime()));
   }

   public boolean matches(UUID currentOwner, ClientOperationWorkspace workspace) {
      if (!owner.equals(currentOwner) || workspace.locked() || workspace.editing()
         || !targets.keySet().equals(workspace.selectedIds())) return false;
      return targets.entrySet().stream().allMatch(entry -> workspace.part(entry.getKey()).isPresent()
         && workspace.interactionId(entry.getKey()) == entry.getValue());
   }
}
