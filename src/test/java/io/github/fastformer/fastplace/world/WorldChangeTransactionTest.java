package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

class WorldChangeTransactionTest {
   @Test
   void ownsRecoveryOrderAndFinalSnapshots() {
      WorldChangeTransaction transaction = new WorldChangeTransaction();
      ReversibleBlockSnapshot first = snapshot(BlockPos.ZERO, "first");
      ReversibleBlockSnapshot second = snapshot(BlockPos.ZERO.above(), "second");

      transaction.recordExpected(first.pos(), first);
      assertEquals(first, transaction.recordExpectedIfAbsent(first.pos(), snapshot(first.pos(), "replacement")));
      assertEquals(first, transaction.expectedAt(new BlockPos(0, 0, 0)));
      assertTrue(transaction.expects(new BlockPos(0, 0, 0)));
      transaction.recordBefore(first);
      transaction.recordBefore(second);
      transaction.recordAfter(first.pos(), snapshot(first.pos(), "final-first"));
      transaction.recordAfter(second.pos(), snapshot(second.pos(), "final-second"));

      assertTrue(transaction.hasWrites());
      assertEquals(1, transaction.expectedCount());
      assertEquals(2, transaction.beforeCount());
      assertEquals(2, transaction.afterCount());
      assertEquals("final-first", marker(transaction.afterAt(first.pos())));
      WorldRecoverySnapshot recovery = transaction.transferRecoverySnapshot();
      assertEquals(second, recovery.before().getFirst());
      assertEquals(
         List.of(first.pos(), second.pos()),
         recovery.after().keySet().stream().toList()
      );
      assertEquals(
         List.of("final-first", "final-second"),
         recovery.after().values().stream().map(WorldChangeTransactionTest::marker).toList()
      );
      assertFalse(transaction.hasWrites());
      assertEquals(0, transaction.afterCount());
      WorldRecoverySnapshot secondTransfer = transaction.transferRecoverySnapshot();
      assertFalse(secondTransfer.hasWrites());
      assertTrue(secondTransfer.after().isEmpty());
   }

   @Test
   void expectedPositionsReuseInsertionOrderWithoutASecondPositionArray() {
      WorldChangeTransaction transaction = new WorldChangeTransaction();
      ReversibleBlockSnapshot first = snapshot(new BlockPos(4, 5, 6), "first");
      ReversibleBlockSnapshot second = snapshot(new BlockPos(-2, 3, 8), "second");

      transaction.recordExpected(first.pos(), first);
      transaction.recordExpected(second.pos(), second);

      var positions = transaction.expectedPositions();
      java.util.ArrayList<BlockPos> ordered = new java.util.ArrayList<>();
      while (positions.hasNext()) {
         ordered.add(positions.next());
      }
      assertEquals(List.of(first.pos(), second.pos()), ordered);
   }

   @Test
   void finalSnapshotDoesNotReplaceTheConflictSnapshot() {
      WorldChangeTransaction transaction = new WorldChangeTransaction();
      BlockPos position = new BlockPos(7, 8, 9);
      ReversibleBlockSnapshot before = snapshot(position, "before");
      ReversibleBlockSnapshot after = snapshot(position, "after");

      transaction.recordExpected(position, before);
      transaction.recordBefore(before);
      transaction.recordAfter(position, after);

      assertEquals("before", marker(transaction.expectedAt(position)));
      assertEquals("after", marker(transaction.afterAt(position)));
   }

   @Test
   void rejectsAnAfterSnapshotForAnotherPosition() {
      WorldChangeTransaction transaction = new WorldChangeTransaction();

      assertThrows(IllegalArgumentException.class, () -> transaction.recordAfter(
         BlockPos.ZERO,
         snapshot(BlockPos.ZERO.above(), "wrong")
      ));
   }

   @Test
   void finalSnapshotsCanReplaceValuesWhilePositionsAreIterated() {
      WorldChangeTransaction transaction = changedTransaction();
      BlockPos second = BlockPos.ZERO.above();
      transaction.recordAfter(second, snapshot(second, "before-finalization"));

      var positions = transaction.afterPositions();
      int finalized = 0;
      while (positions.hasNext()) {
         BlockPos position = positions.next();
         transaction.recordAfter(position, snapshot(position, "finalized"));
         finalized++;
      }

      assertEquals(2, finalized);
      assertTrue(transaction.transferRecoverySnapshot().after().values().stream()
         .allMatch(value -> marker(value).equals("finalized")));
   }

   @Test
   void commitDoesNotStartUntilMemoryIsReserved() {
      WorldChangeTransaction transaction = changedTransaction();
      AtomicInteger attempts = new AtomicInteger();

      assertEquals(JournalPreparation.PENDING, transaction.prepareCommit(
         Level.OVERWORLD,
         null,
         () -> {
            attempts.incrementAndGet();
            return false;
         }
      ));

      assertEquals(1, attempts.get());
      assertFalse(transaction.commitStarted());
   }

   @Test
   void preparedBatchKeepsOperationIdentityAndCommittedStateCanBeReleased() {
      WorldChangeTransaction transaction = changedTransaction();
      UUID operationId = UUID.randomUUID();

      JournalPreparation result = await(() -> transaction.prepareCommit(Level.OVERWORLD, null, () -> true));

      assertEquals(JournalPreparation.READY, result);
      WorldChangeBatch batch = transaction.preparedBatch(operationId).orElseThrow();
      assertEquals(operationId, batch.operationId());
      assertEquals(1, batch.size());

      transaction.releaseCommitted();
      assertFalse(transaction.hasWrites());
      assertEquals(0, transaction.expectedCount());
      assertEquals(0, transaction.afterCount());
      assertFalse(transaction.commitStarted());
   }

   @Test
   void recoveryTransferIsImmediatelyReadyWithoutCommitWork() {
      WorldRecoverySnapshot recovery = changedTransaction().transferRecoverySnapshot();

      assertTrue(recovery.ready());
      assertTrue(recovery.hasWrites());
   }

   private static WorldChangeTransaction changedTransaction() {
      WorldChangeTransaction transaction = new WorldChangeTransaction();
      transaction.recordBefore(snapshot(BlockPos.ZERO, "before"));
      transaction.recordAfter(BlockPos.ZERO, snapshot(BlockPos.ZERO, "after"));
      return transaction;
   }

   private static JournalPreparation await(ThrowingSupplier<JournalPreparation> poll) {
      return org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
         JournalPreparation result;
         do {
            result = poll.get();
            Thread.onSpinWait();
         } while (result == JournalPreparation.PENDING);
         return result;
      });
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos position, String marker) {
      CompoundTag data = new CompoundTag();
      data.putString("marker", marker);
      return new ReversibleBlockSnapshot(position, null, null, new BlockEntitySnapshot(data));
   }

   private static String marker(ReversibleBlockSnapshot snapshot) {
      return snapshot.blockEntity().data().getString("marker");
   }
}
