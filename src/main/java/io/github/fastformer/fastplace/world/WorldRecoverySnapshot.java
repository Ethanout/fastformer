package io.github.fastformer.fastplace.world;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;

/**
 * Mutable snapshot storage transferred from a failed world operation to its
 * recovery task. The producing transaction must not use the containers after
 * creating this value.
 */
public record WorldRecoverySnapshot(
   ArrayDeque<ReversibleBlockSnapshot> before,
   Map<BlockPos, ReversibleBlockSnapshot> after,
   CompletableFuture<Void> readyForRecovery
) {
   public WorldRecoverySnapshot {
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      Objects.requireNonNull(readyForRecovery, "readyForRecovery");
   }

   public boolean hasWrites() {
      return !this.before.isEmpty();
   }

   public boolean ready() {
      return this.readyForRecovery.isDone();
   }
}
