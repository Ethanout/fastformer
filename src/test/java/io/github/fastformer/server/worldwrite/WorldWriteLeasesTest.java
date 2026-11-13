package io.github.fastformer.server.worldwrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldWriteCoordinatorTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void aSecondTransactionOfOneOwnerCannotEnterWhileTheFirstHoldsTheLease() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteLeases.Lease first = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(first);
      assertTrue(server.busy(Level.OVERWORLD));
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      // A nested request from the same player must not join the live
      // transaction, because it would release the lease that the first
      // transaction still uses.
      assertNull(server.acquire(Level.OVERWORLD, owner));
      assertNull(server.acquire(Level.OVERWORLD, otherOwner));

      assertTrue(first.release());
      assertFalse(server.busy(Level.OVERWORLD));
      assertNotNull(server.acquire(Level.OVERWORLD, otherOwner));
      server.close();
   }

   @Test
   void recoveryTakeoverKeepsTheLeaseAndIgnoresThePredecessorRelease() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteLeases.Lease writer = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(writer);

      // The recovery task inherits the lease of the same owner. The takeover
      // must not open a window for another writer.
      WorldWriteLeases.Lease recovery = server.takeOver(Level.OVERWORLD, owner);
      assertNotNull(recovery);
      assertTrue(server.busy(Level.OVERWORLD));
      assertNull(server.acquire(Level.OVERWORLD, otherOwner));

      // The predecessor's late release belongs to an older generation.
      assertFalse(writer.release());
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertTrue(server.busy(Level.OVERWORLD));
      assertNull(server.acquire(Level.OVERWORLD, otherOwner));

      assertTrue(recovery.release());
      assertFalse(server.busy(Level.OVERWORLD));
      assertNotNull(server.acquire(Level.OVERWORLD, otherOwner));
      server.close();
   }

   @Test
   void aReleasedLeaseCanNeverFreeALaterLeaseOfTheSameOwner() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();

      WorldWriteLeases.Lease finished = server.acquire(Level.OVERWORLD, owner);
      assertTrue(finished.release());

      WorldWriteLeases.Lease current = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(current);

      // Generations are never reused, so the token of the finished transaction
      // cannot release the transaction that runs now.
      assertFalse(finished.release());
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertNull(server.acquire(Level.OVERWORLD, otherOwner));

      assertTrue(current.release());
      assertFalse(server.busy(Level.OVERWORLD));
      server.close();
   }

   @Test
   void aLeaseCannotBeReleasedTwiceOrByAStranger() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      UUID stranger = UUID.randomUUID();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertTrue(lease.release());
      assertFalse(lease.release());
      assertFalse(server.releaseCurrentLease(Level.OVERWORLD, stranger));
      assertFalse(server.busy(Level.OVERWORLD));
      server.close();
   }

   @Test
   void renewReportsWhetherOneLeaseStillOwnsTheDimension() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertTrue(lease.isCurrent());

      WorldWriteLeases.Lease successor = server.takeOver(Level.OVERWORLD, owner);
      assertFalse(lease.isCurrent());
      assertTrue(successor.isCurrent());

      server.close();
      assertFalse(successor.isCurrent());
   }

   @Test
   void aTakeoverStartsAFreshLeaseWhenTheOwnerHoldsNothing() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();

      WorldWriteLeases.Lease lease = server.takeOver(Level.OVERWORLD, owner);
      assertNotNull(lease);
      assertEquals(owner, lease.owner());
      assertEquals(Level.OVERWORLD, lease.dimension());
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      server.close();
   }

   @Test
   void dimensionsAndServersHaveIndependentLeases() {
      WorldWriteLeases firstServer = new WorldWriteLeases();
      WorldWriteLeases secondServer = new WorldWriteLeases();
      UUID firstOwner = UUID.randomUUID();
      UUID secondOwner = UUID.randomUUID();

      assertNotNull(firstServer.acquire(Level.OVERWORLD, firstOwner));
      assertNotNull(firstServer.acquire(Level.NETHER, secondOwner));
      assertNotNull(secondServer.acquire(Level.OVERWORLD, secondOwner));
      assertTrue(firstServer.heldBy(Level.OVERWORLD, firstOwner));

      firstServer.close();
      assertFalse(firstServer.heldBy(Level.OVERWORLD, firstOwner));
      assertTrue(secondServer.heldBy(Level.OVERWORLD, secondOwner));
      secondServer.close();
   }

   @Test
   void clearingOneServerAndItsLateCleanupCannotReleaseAnotherLiveServer() {
      WorldWriteLeases firstServer = new WorldWriteLeases();
      WorldWriteLeases liveServer = new WorldWriteLeases();
      UUID firstOwner = UUID.randomUUID();
      UUID liveOwner = UUID.randomUUID();
      var pending = new CompletableFuture<Optional<PersistentRecoveryJournal>>();
      try {
         var first = firstServer.acquire(Level.OVERWORLD, firstOwner);
         var live = liveServer.acquire(Level.OVERWORLD, liveOwner);
         assertNotNull(first);
         assertNotNull(live);
         first.releaseAfterCleanup(null, pending);
         firstServer.close();
         assertFalse(first.isCurrent());
         assertTrue(live.isCurrent());
         pending.complete(Optional.empty());
         assertFalse(firstServer.busy(Level.OVERWORLD));
         assertTrue(live.isCurrent());
      } finally {
         pending.complete(Optional.empty());
         firstServer.close();
         liveServer.close();
      }
   }

   @Test
   void sameOwnerCannotOpenASecondDimensionTransaction() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      assertNull(server.acquire(Level.NETHER, owner));
      assertNull(server.takeOver(Level.NETHER, owner));
      assertTrue(lease.release());
      assertNotNull(server.acquire(Level.NETHER, owner));
      server.close();
   }

   @Test
   void cancelledJournalPreparationKeepsLeaseUntilCleanupCompletes() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      lease.releaseAfterCleanup(null, pending);
      assertTrue(server.busy(Level.OVERWORLD));

      pending.complete(Optional.empty());
      assertFalse(server.busy(Level.OVERWORLD));
      server.close();
   }

   @Test
   void failedPreparationWithoutAJournalReleasesLease() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> failed = new CompletableFuture<>();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      lease.releaseAfterCleanup(null, failed);
      failed.completeExceptionally(new IllegalStateException("preparation failed before journal creation"));

      assertFalse(server.busy(Level.OVERWORLD));
      server.close();
   }

   @Test
   void failedUnusedJournalDeletionIsRetriedBeforeTheLeaseCanBeReused() throws IOException {
      var clock = new java.util.concurrent.atomic.AtomicLong();
      WorldWriteLeases server = new WorldWriteLeases(clock::get);
      UUID owner = UUID.randomUUID();
      Path blockedJournal = this.temporaryDirectory.resolve("blocked.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      lease.releaseAfterCleanup(RecoveryJournalTestAccess.open(blockedJournal), null);
      assertTrue(server.busy(Level.OVERWORLD));
      assertNull(server.acquire(Level.OVERWORLD, owner));
      assertNull(server.acquire(Level.NETHER, owner));

      Files.delete(blockerContent);
      Files.delete(blocker);
      clock.addAndGet(1_000_000_000L);
      assertFalse(server.busy(Level.OVERWORLD));
      assertNotNull(server.acquire(Level.NETHER, owner));
      server.close();
   }

   @Test
   void lateCleanupCallbackCannotResurrectAClearedServerLease() throws IOException {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();
      Path blockedJournal = this.temporaryDirectory.resolve("late.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      lease.releaseAfterCleanup(null, pending);
      server.close();
      pending.complete(Optional.of(RecoveryJournalTestAccess.open(blockedJournal)));

      assertFalse(server.busy(Level.OVERWORLD));
      Files.delete(blockerContent);
      Files.delete(blocker);
      Files.delete(blockedJournal);
   }

   @Test
   void aLateJournalCleanupCannotReleaseASuccessorLeaseOfTheSameOwner() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      UUID otherOwner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();

      WorldWriteLeases.Lease cancelled = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(cancelled);
      cancelled.releaseAfterCleanup(null, pending);

      // The cancelled transaction ends and the same player starts another one.
      assertTrue(cancelled.release());
      WorldWriteLeases.Lease successor = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(successor);

      // The late callback of the cancelled preparation must not free the lease
      // of the new transaction.
      pending.complete(Optional.empty());
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertTrue(server.busy(Level.OVERWORLD));
      assertNull(server.acquire(Level.OVERWORLD, otherOwner));

      assertTrue(successor.release());
      server.close();
   }

   @Test
   void aLateFailedJournalCleanupCannotReleaseASuccessorLeaseOfTheSameOwner() throws IOException {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      CompletableFuture<Optional<PersistentRecoveryJournal>> pending = new CompletableFuture<>();
      Path blockedJournal = this.temporaryDirectory.resolve("late-blocked.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteLeases.Lease cancelled = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(cancelled);
      cancelled.releaseAfterCleanup(null, pending);
      assertTrue(cancelled.release());
      WorldWriteLeases.Lease successor = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(successor);

      // The callback discovers a real file that it cannot delete. The pending
      // cleanup must not release the successor lease either.
      pending.complete(Optional.of(RecoveryJournalTestAccess.open(blockedJournal)));
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertTrue(successor.release());

      Files.delete(blockerContent);
      Files.delete(blocker);
      server.close();
      Files.delete(blockedJournal);
   }

   @Test
   void aRetriedJournalCleanupReleasesItsOwnLeaseAndNotTheSuccessor() throws IOException {
      var clock = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE - 500_000_000L);
      WorldWriteLeases server = new WorldWriteLeases(clock::get);
      UUID owner = UUID.randomUUID();
      Path blockedJournal = this.temporaryDirectory.resolve("retry-successor.dat");
      Files.createDirectories(blockedJournal);
      Path blocker = Files.createDirectory(blockedJournal.resolve("still-open"));
      Path blockerContent = Files.writeString(blocker.resolve("content"), "block deletion");

      WorldWriteLeases.Lease first = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(first);
      first.releaseAfterCleanup(RecoveryJournalTestAccess.open(blockedJournal), null);
      // The pending cleanup still owns the durable proof, so the lease stays.
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertFalse(first.release());
      assertNull(server.acquire(Level.OVERWORLD, owner));

      Files.delete(blockerContent);
      Files.delete(blocker);
      clock.addAndGet(1_000_000_000L);
      assertFalse(server.busy(Level.OVERWORLD));

      // The cleanup released its own generation. The successor lease of the
      // same player stays live, and the stale token stays inert.
      WorldWriteLeases.Lease successor = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(successor);
      assertFalse(first.release());
      assertTrue(server.heldBy(Level.OVERWORLD, owner));
      assertTrue(successor.release());
      server.close();
   }

   @Test
   void journalCleanupWithoutALeaseIdentityNeverFreesAnotherTransaction() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();

      WorldWriteLeases.Lease lease = server.acquire(Level.OVERWORLD, owner);
      assertNotNull(lease);
      // A caller without a transaction identity must not fall back to the
      // player key, because the same player may own the dimension now.
      new WorldJournalPreparation(Runnable::run).releaseAfterCancellation(null);
      assertTrue(server.heldBy(Level.OVERWORLD, owner));

      assertTrue(lease.release());
      server.close();
   }

   @Test
   void closedRuntimeRejectsAllNewWritersAndOldTokensCannotAffectItsReplacement() {
      var stopped = new WorldWriteLeases();
      var running = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      var old = stopped.acquire(Level.OVERWORLD, owner);
      var current = running.acquire(Level.OVERWORLD, owner);
      stopped.close();
      stopped.close();
      assertNull(stopped.acquire(Level.OVERWORLD, owner));
      assertNull(stopped.takeOver(Level.OVERWORLD, owner));
      assertFalse(old.release());
      assertFalse(stopped.releaseCurrentLease(Level.OVERWORLD, owner));
      assertTrue(current.isCurrent());
      assertTrue(current.release());
   }

   @Test
   void throwingCleanupKeepsItsLeaseAndRetriesOnlyAfterTheDeadline() {
      var clock = new java.util.concurrent.atomic.AtomicLong();
      var writes = new WorldWriteLeases(clock::get);
      var attempts = new java.util.concurrent.atomic.AtomicInteger();
      UUID owner = UUID.randomUUID();
      var lease = writes.acquire(Level.OVERWORLD, owner);
      var creation = new CompletableFuture<Optional<UnusedWriteCleanup>>();
      lease.releaseAfterCleanup(null, creation);
      creation.complete(Optional.of(() -> {
         if (attempts.incrementAndGet() == 1) throw new IllegalStateException("expected cleanup failure");
         return true;
      }));
      assertFalse(lease.release());
      assertNull(writes.takeOver(Level.OVERWORLD, owner));
      clock.set(999_999_999L);
      assertTrue(writes.busy(Level.OVERWORLD));
      assertEquals(1, attempts.get());
      clock.incrementAndGet();
      assertNotNull(writes.acquire(Level.NETHER, owner));
      assertEquals(2, attempts.get());
      assertFalse(writes.busy(Level.OVERWORLD));
   }
}
