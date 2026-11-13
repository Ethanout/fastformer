package io.github.fastformer.workspace.submission;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Immutable task result independent of online player identity and packet delivery. */
public record WorkspaceCompletion(UUID owner, ResourceLocation dimension, UUID transfer,
   boolean applied, boolean retryable, boolean recoveryCreated,
   List<Integer> failedIds, List<BlockPos> failedTargets, WorkspaceFailure failure) {
   public WorkspaceCompletion {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(dimension);
      Objects.requireNonNull(transfer);
      Objects.requireNonNull(failure);
      failedIds = List.copyOf(failedIds);
      failedTargets = failedTargets.stream().map(BlockPos::immutable).toList();
   }
}
