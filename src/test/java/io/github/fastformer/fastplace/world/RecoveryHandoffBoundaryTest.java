package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.TaskCancellationResult;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The reception boundary of a transferred recovery snapshot.
 *
 * <p>A caller keeps the snapshot until the manager accepts it, and hands the same snapshot
 * over again when the call reports a failure. Acceptance must therefore be observable
 * exactly once: work that runs after the transfer must never turn an accepted snapshot into
 * a reported failure.
 *
 * <p>The failing-notification case needs a context whose message path throws. That shell
 * cannot be built in a plain unit test JVM, so it lives in
 * {@link RecoveryHandoffBoundaryGameTests} and fails hard there instead of skipping.
 */
class RecoveryHandoffBoundaryTest {
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      Registries.DIMENSION,
      ResourceLocation.fromNamespaceAndPath("fastformer", "recovery_handoff_boundary_test")
   );

   @AfterEach
   void clearState() {
      WorldHistoryManager.clearServer();
      WorldWriteCoordinator.clearAll();
   }

   @Test
   void aFailedMemoryReleaseCannotHideTheAcceptedResult() {
      UUID owner = UUID.randomUUID();

      TaskCancellationResult result = WorldHistoryManager.acceptTransferredRecovery(
         new WorldTaskContext(null, owner),
         DIMENSION,
         notReadySnapshot(),
         null,
         () -> {},
         () -> { throw new IllegalStateException("memory release failed"); }
      );

      assertEquals(TaskCancellationResult.ROLLBACK_STARTED, result);
      assertEquals(1, WorldHistoryManager.recoveryCaptureCountForTest(owner));
   }

   @Test
   void theUnusedJournalCleanupKeepsTheLeaseUntilTheJournalSignalSettles() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, DIMENSION, owner);
      assertNotNull(lease);
      CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture = new CompletableFuture<>();

      // This is the release that the hand-off runs for a snapshot without writes:
      // WorldWriteCoordinator.releaseAfterUnusedJournal, reached through
      // WorldJournalPreparation.releaseAfterCancellation.
      WorldWriteCoordinator.releaseAfterUnusedJournal(lease, null, journalFuture);

      // The cancelled journal may still append, so a successor must not enter yet.
      assertTrue(WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      assertNull(WorldWriteCoordinator.acquire(server, DIMENSION, owner));

      journalFuture.complete(Optional.empty());

      // The journal signal settled, so the cleanup released the lease and the next
      // transaction can start.
      assertFalse(WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      assertNotNull(WorldWriteCoordinator.acquire(server, DIMENSION, owner));
   }

   /** A real asynchronous snapshot that the producing writer has not finished yet. */
   private static WorldRecoverySnapshot notReadySnapshot() {
      BlockPos pos = new BlockPos(1, 2, 3);
      return new WorldRecoverySnapshot(
         new ArrayDeque<>(List.of(snapshot(pos, "before"))),
         Map.of(pos, snapshot(pos, "after")),
         new CompletableFuture<>()
      );
   }

   /**
    * The failing-notification case moved to {@link RecoveryHandoffBoundaryGameTests}: the
    * server shell it needs cannot be built in a plain unit test JVM.
    */
   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }
}
