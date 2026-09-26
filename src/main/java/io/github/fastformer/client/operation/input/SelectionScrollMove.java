package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;

public record SelectionScrollMove(UUID owner, Map<Integer, Long> targets, BlockPos offset) {
   public SelectionScrollMove {
      targets = Map.copyOf(targets);
      offset = offset.immutable();
   }

   public static Optional<SelectionScrollMove> capture(UUID owner, ClientOperationWorkspace workspace, BlockPos offset) {
      if (workspace.locked() || workspace.editing() || workspace.selectedIds().isEmpty()
         || offset.equals(BlockPos.ZERO)) return Optional.empty();
      var targets = workspace.selectedIds().stream().collect(Collectors.toMap(id -> id, workspace::interactionId));
      return Optional.of(new SelectionScrollMove(owner, targets, offset));
   }

   public boolean matches(UUID currentOwner, ClientOperationWorkspace workspace) {
      if (!owner.equals(currentOwner) || workspace.locked() || workspace.editing()
         || !targets.keySet().equals(workspace.selectedIds())) return false;
      return targets.entrySet().stream().allMatch(entry -> workspace.part(entry.getKey()).isPresent()
         && workspace.interactionId(entry.getKey()) == entry.getValue());
   }
}
