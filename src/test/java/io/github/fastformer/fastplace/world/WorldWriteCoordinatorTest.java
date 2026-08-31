package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldWriteCoordinatorTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void sameOwnerCanTransferLeaseToRecoveryButAnotherOwnerMustWait() {
      Object server = new Object();
      UUID placementOwner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, placementOwner));
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, placementOwner));
      assertFalse(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, otherOwner));

      WorldWriteCoordinator.release(server, Level.OVERWORLD, placementOwner);
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, otherOwner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void dimensionsAndServersHaveIndependentLeases() {
      Object firstServer = new Object();
      Object secondServer = new Object();
      UUID firstOwner = UUID.randomUUID();
      UUID secondOwner = UUID.randomUUID();

      assertTrue(WorldWriteCoordinator.tryAcquire(firstServer, Level.OVERWORLD, firstOwner));
      assertTrue(WorldWriteCoordinator.tryAcquire(firstServer, Level.NETHER, secondOwner));
      assertTrue(WorldWriteCoordinator.tryAcquire(secondServer, Level.OVERWORLD, secondOwner));
      assertTrue(WorldWriteCoordinator.heldBy(firstServer, Level.OVERWORLD, firstOwner));

      WorldWriteCoordinator.clear(firstServer);
      assertFalse(WorldWriteCoordinator.heldBy(firstServer, Level.OVERWORLD, firstOwner));
      assertTrue(WorldWriteCoordinator.heldBy(secondServer, Level.OVERWORLD, secondOwner));
      WorldWriteCoordinator.clear(secondServer);
   }

   @Test
   void newServerLifecycleCanDiscardEveryStaleServerLease() {
      Object firstServer = new Object();
      Object staleServer = new Object();
      UUID firstOwner = UUID.randomUUID();
      UUID staleOwner = UUID.randomUUID();

      assertTrue(WorldWriteCoordinator.tryAcquire(firstServer, Level.OVERWORLD, firstOwner));
      assertTrue(WorldWriteCoordinator.tryAcquire(staleServer, Level.NETHER, staleOwner));

      WorldWriteCoordinator.clearAll();

      assertFalse(WorldWriteCoordinator.heldBy(firstServer, Level.OVERWORLD, firstOwner));
      assertFalse(WorldWriteCoordinator.heldBy(staleServer, Level.NETHER, staleOwner));
   }

   @Test
   void wrongOwnerCannotReleaseAnotherTransaction() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID stranger = UUID.randomUUID();

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.release(server, Level.OVERWORLD, stranger);

      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertFalse(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, stranger));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void sameOwnerCannotOpenASecondDimensionTransaction() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      assertFalse(WorldWriteCoordinator.tryAcquire(server, Level.NETHER, owner));
      WorldWriteCoordinator.release(server, Level.OVERWORLD, owner);
      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.NETHER, owner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void cancelledJournalPreparationKeepsLeaseUntilCleanupCompletes() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.releaseAfterUnusedJournal(server, Level.OVERWORLD, owner, null, pending);
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));

      pending.complete(Optional.empty());
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void failedPreparationWithoutAJournalReleasesLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> failed = new CompletableFuture<>();

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.releaseAfterUnusedJournal(server, Level.OVERWORLD, owner, null, failed);
      failed.completeExceptionally(new IllegalStateException("preparation failed before journal creation"));

      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void failedUnusedJournalDeletionIsRetriedBeforeTheLeaseCanBeReused() throws IOException {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      Path blockedJournal = this.temporaryDirectory.resolve("blocked.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = blockedJournal.resolve("still-open");
      Files.writeString(blocker, "block deletion");

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.releaseAfterUnusedJournal(
         server,
         Level.OVERWORLD,
         owner,
         new PersistentRecoveryJournal(blockedJournal),
         null
      );
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertFalse(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      assertFalse(WorldWriteCoordinator.tryAcquire(server, Level.NETHER, owner));

      Files.delete(blocker);
      WorldWriteCoordinator.retryUnusedJournalForTest(server, Level.OVERWORLD);
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.NETHER, owner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void lateCleanupCallbackCannotResurrectAClearedServerLease() throws IOException {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();
      Path blockedJournal = this.temporaryDirectory.resolve("late.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = blockedJournal.resolve("still-open");
      Files.writeString(blocker, "block deletion");

      assertTrue(WorldWriteCoordinator.tryAcquire(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.releaseAfterUnusedJournal(server, Level.OVERWORLD, owner, null, pending);
      WorldWriteCoordinator.clear(server);
      pending.complete(Optional.of(new PersistentRecoveryJournal(blockedJournal)));

      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      Files.delete(blocker);
      Files.delete(blockedJournal);
   }
}
