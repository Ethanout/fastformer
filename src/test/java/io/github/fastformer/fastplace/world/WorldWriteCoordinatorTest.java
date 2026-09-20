package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
   void aSecondTransactionOfOneOwnerCannotEnterWhileTheFirstHoldsTheLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteCoordinator.Lease first = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(first);
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      // A nested request from the same player must not join the live
      // transaction, because it would release the lease that the first
      // transaction still uses.
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));

      assertTrue(WorldWriteCoordinator.release(first));
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNotNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void recoveryTakeoverKeepsTheLeaseAndIgnoresThePredecessorRelease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteCoordinator.Lease writer = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(writer);

      // The recovery task inherits the lease of the same owner. The takeover
      // must not open a window for another writer.
      WorldWriteCoordinator.Lease recovery = WorldWriteCoordinator.takeOver(server, Level.OVERWORLD, owner);
      assertNotNull(recovery);
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));

      // The predecessor's late release belongs to an older generation.
      assertFalse(WorldWriteCoordinator.release(writer));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));

      assertTrue(WorldWriteCoordinator.release(recovery));
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNotNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void aReleasedLeaseCanNeverFreeALaterLeaseOfTheSameOwner() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteCoordinator.Lease finished = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertTrue(WorldWriteCoordinator.release(finished));

      WorldWriteCoordinator.Lease current = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(current);

      // Generations are never reused, so the token of the finished transaction
      // cannot release the transaction that runs now.
      assertFalse(WorldWriteCoordinator.release(finished));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));

      assertTrue(WorldWriteCoordinator.release(current));
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void aLeaseCannotBeReleasedTwiceOrByAStranger() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID stranger = UUID.randomUUID();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertTrue(WorldWriteCoordinator.release(lease));
      assertFalse(WorldWriteCoordinator.release(lease));
      assertFalse(WorldWriteCoordinator.releaseCurrentLease(server, Level.OVERWORLD, stranger));
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void renewReportsWhetherOneLeaseStillOwnsTheDimension() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertTrue(WorldWriteCoordinator.renew(lease));

      WorldWriteCoordinator.Lease successor = WorldWriteCoordinator.takeOver(server, Level.OVERWORLD, owner);
      assertFalse(WorldWriteCoordinator.renew(lease));
      assertTrue(WorldWriteCoordinator.renew(successor));

      WorldWriteCoordinator.clear(server);
      assertFalse(WorldWriteCoordinator.renew(successor));
   }

   @Test
   void aTakeoverStartsAFreshLeaseWhenTheOwnerHoldsNothing() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.takeOver(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      assertEquals(owner, lease.owner());
      assertEquals(Level.OVERWORLD, lease.dimension());
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void dimensionsAndServersHaveIndependentLeases() {
      Object firstServer = new Object();
      Object secondServer = new Object();
      UUID firstOwner = UUID.randomUUID();
      UUID secondOwner = UUID.randomUUID();

      assertNotNull(WorldWriteCoordinator.acquire(firstServer, Level.OVERWORLD, firstOwner));
      assertNotNull(WorldWriteCoordinator.acquire(firstServer, Level.NETHER, secondOwner));
      assertNotNull(WorldWriteCoordinator.acquire(secondServer, Level.OVERWORLD, secondOwner));
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

      assertNotNull(WorldWriteCoordinator.acquire(firstServer, Level.OVERWORLD, firstOwner));
      assertNotNull(WorldWriteCoordinator.acquire(staleServer, Level.NETHER, staleOwner));

      WorldWriteCoordinator.clearAll();

      assertFalse(WorldWriteCoordinator.heldBy(firstServer, Level.OVERWORLD, firstOwner));
      assertFalse(WorldWriteCoordinator.heldBy(staleServer, Level.NETHER, staleOwner));
   }

   @Test
   void sameOwnerCannotOpenASecondDimensionTransaction() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      assertNull(WorldWriteCoordinator.acquire(server, Level.NETHER, owner));
      assertNull(WorldWriteCoordinator.takeOver(server, Level.NETHER, owner));
      assertTrue(WorldWriteCoordinator.release(lease));
      assertNotNull(WorldWriteCoordinator.acquire(server, Level.NETHER, owner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void cancelledJournalPreparationKeepsLeaseUntilCleanupCompletes() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      WorldWriteCoordinator.releaseAfterUnusedJournal(lease, null, pending);
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

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      WorldWriteCoordinator.releaseAfterUnusedJournal(lease, null, failed);
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
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      WorldWriteCoordinator.releaseAfterUnusedJournal(lease, new PersistentRecoveryJournal(blockedJournal), null);
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner));
      assertNull(WorldWriteCoordinator.acquire(server, Level.NETHER, owner));

      Files.delete(blockerContent);
      Files.delete(blocker);
      WorldWriteCoordinator.retryUnusedJournalForTest(server, Level.OVERWORLD);
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNotNull(WorldWriteCoordinator.acquire(server, Level.NETHER, owner));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void lateCleanupCallbackCannotResurrectAClearedServerLease() throws IOException {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();
      Path blockedJournal = this.temporaryDirectory.resolve("late.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      WorldWriteCoordinator.releaseAfterUnusedJournal(lease, null, pending);
      WorldWriteCoordinator.clear(server);
      pending.complete(Optional.of(new PersistentRecoveryJournal(blockedJournal)));

      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      Files.delete(blockerContent);
      Files.delete(blocker);
      Files.delete(blockedJournal);
   }

   @Test
   void aLateJournalCleanupCannotReleaseASuccessorLeaseOfTheSameOwner() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();

      WorldWriteCoordinator.Lease cancelled = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(cancelled);
      WorldWriteCoordinator.releaseAfterUnusedJournal(cancelled, null, pending);

      // The cancelled transaction ends and the same player starts another one.
      assertTrue(WorldWriteCoordinator.release(cancelled));
      WorldWriteCoordinator.Lease successor = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(successor);

      // The late callback of the cancelled preparation must not free the lease
      // of the new transaction.
      pending.complete(Optional.empty());
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertTrue(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, otherOwner));

      assertTrue(WorldWriteCoordinator.release(successor));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void aLateFailedJournalCleanupCannotReleaseASuccessorLeaseOfTheSameOwner() throws IOException {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();
      Path blockedJournal = this.temporaryDirectory.resolve("late-blocked.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteCoordinator.Lease cancelled = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(cancelled);
      WorldWriteCoordinator.releaseAfterUnusedJournal(cancelled, null, pending);
      assertTrue(WorldWriteCoordinator.release(cancelled));
      WorldWriteCoordinator.Lease successor = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(successor);

      // The callback discovers a real file that it cannot delete. The pending
      // cleanup must not release the successor lease either.
      pending.complete(Optional.of(new PersistentRecoveryJournal(blockedJournal)));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertTrue(WorldWriteCoordinator.release(successor));

      Files.delete(blockerContent);
      Files.delete(blocker);
      WorldWriteCoordinator.retryUnusedJournalForTest(server, Level.OVERWORLD);
      WorldWriteCoordinator.clear(server);
      Files.delete(blockedJournal);
   }

   @Test
   void aRetriedJournalCleanupReleasesItsOwnLeaseAndNotTheSuccessor() throws IOException {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      Path blockedJournal = this.temporaryDirectory.resolve("retry-successor.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteCoordinator.Lease first = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(first);
      WorldWriteCoordinator.releaseAfterUnusedJournal(first, new PersistentRecoveryJournal(blockedJournal), null);
      // The pending cleanup still owns the durable proof, so the lease stays.
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertFalse(WorldWriteCoordinator.release(first));
      assertNull(WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner));

      Files.delete(blockerContent);
      Files.delete(blocker);
      WorldWriteCoordinator.retryUnusedJournalForTest(server, Level.OVERWORLD);
      assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));

      // The cleanup released its own generation. The successor lease of the
      // same player stays live, and the stale token stays inert.
      WorldWriteCoordinator.Lease successor = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(successor);
      assertFalse(WorldWriteCoordinator.release(first));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
      assertTrue(WorldWriteCoordinator.release(successor));
      WorldWriteCoordinator.clear(server);
   }

   @Test
   void journalCleanupWithoutALeaseIdentityNeverFreesAnotherTransaction() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();

      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, Level.OVERWORLD, owner);
      assertNotNull(lease);
      // A caller without a transaction identity must not fall back to the
      // player key, because the same player may own the dimension now.
      WorldWriteCoordinator.releaseAfterUnusedJournal(null, null, null);
      WorldWriteCoordinator.releaseAfterUnusedJournal(null, null, CompletableFuture.completedFuture(Optional.empty()));
      assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));

      assertTrue(WorldWriteCoordinator.release(lease));
      WorldWriteCoordinator.clear(server);
   }
}
