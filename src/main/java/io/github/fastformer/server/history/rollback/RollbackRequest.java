package io.github.fastformer.server.history.rollback;

import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Transfers a detached writer capture, including any outstanding journal append. */
public record RollbackRequest(
   WorldTaskContext context,
   ResourceKey<Level> dimension,
   WorldRecoverySnapshot snapshots,
   PersistentRecoveryJournal journal
) {
   public RollbackRequest {
      Objects.requireNonNull(context, "context");
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(snapshots, "snapshots");
   }

   /** The caller must stop using these containers once rollback accepts the request. */
   public static RollbackRequest ready(WorldTaskContext context, ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> before, Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal) {
      return new RollbackRequest(context, dimension, new WorldRecoverySnapshot(before,
         after == null ? Map.of() : after, CompletableFuture.completedFuture(null)), journal);
   }
}
