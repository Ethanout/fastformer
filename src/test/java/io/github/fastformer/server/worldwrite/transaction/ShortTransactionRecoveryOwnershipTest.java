package io.github.fastformer.server.worldwrite.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import io.github.fastformer.server.recovery.gate.RecoveryWriteGate;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the hand-off contract of a failed short world transaction.
 *
 * <p>A short transaction that cannot restore its writes must give the capture
 * to a recovery owner. The result may report recovery waiting only after that
 * owner takes the capture. A refused hand-off must report a blocked recovery
 * and leave the capture with the caller, never claim a recovery that does not
 * exist.</p>
 */
class ShortTransactionRecoveryOwnershipTest {
   private final WorldWriteLeases server = new WorldWriteLeases();
   private static final HistoryService history = new HistoryService(
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true),
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true), new io.github.fastformer.server.history.NoDiskHistoryRepository());
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      Registries.DIMENSION,
      ResourceLocation.fromNamespaceAndPath("fastformer", "short_transaction_ownership_test")
   );

   @AfterEach
   void clearState() {
      history.clear();
      server.close();
   }

   @Test
   void anAcceptedHandOffGivesTheCaptureToTheRecoveryQueueAndKeepsTheLease() {
      UUID owner = UUID.randomUUID();
      WorldWriteLeases.Lease lease = server.acquire(DIMENSION, owner);
      assertNotNull(lease);
      BlockPos pos = new BlockPos(3, 4, 5);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));

      // An incomplete pair set stays queued, so the recovery queue owns it.
      BlockEditTransaction.Outcome outcome = BlockEditTransaction.handOffRecovery(
         context(owner), DIMENSION, before, Map.of()
      , history);

      assertEquals(BlockEditTransaction.Outcome.RECOVERY_PENDING, outcome);
      assertEquals(1, HistoryTestAccess.recoveryCaptureCount(history, owner));
      assertTrue(server.heldBy(DIMENSION, owner));
      assertTrue(outcome.consumedInteraction());
   }

   @Test
   void aRefusedHandOffReportsABlockedRecoveryAndNeverClaimsARecovery() {
      UUID owner = UUID.randomUUID();
      WorldWriteLeases.Lease lease = server.acquire(DIMENSION, owner);
      assertNotNull(lease);
      BlockPos pos = new BlockPos(6, 7, 8);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));

      // A capture without a dimension has no recovery owner.
      BlockEditTransaction.Outcome outcome = BlockEditTransaction.handOffRecovery(
         context(owner), null, before, Map.of()
      , history);

      assertNotEquals(BlockEditTransaction.Outcome.RECOVERY_PENDING, outcome);
      assertEquals(BlockEditTransaction.Outcome.RECOVERY_BLOCKED, outcome);
      assertEquals(0, HistoryTestAccess.recoveryCaptureCount(history, owner));
      // The write lock and the capture stay with the caller, so another writer
      // cannot enter the half-written world.
      assertTrue(server.heldBy(DIMENSION, owner));
      assertEquals(1, before.size());
      assertNotNull(before.peekFirst());
      assertTrue(outcome.consumedInteraction());
   }

   @Test
   void anEmptyOrMissingCaptureIsNotHandedOff() {
      UUID owner = UUID.randomUUID();
      WorldWriteLeases.Lease lease = server.acquire(DIMENSION, owner);
      assertNotNull(lease);

      // No capture means no recovery work. The lease stays with the caller
      // because the transaction never released it.
      assertEquals(
         BlockEditTransaction.Outcome.RECOVERY_BLOCKED,
         BlockEditTransaction.handOffRecovery(context(owner), DIMENSION, new ArrayDeque<>(), Map.of(), history)
      );
      assertEquals(
         BlockEditTransaction.Outcome.RECOVERY_BLOCKED,
         BlockEditTransaction.handOffRecovery(context(owner), DIMENSION, null, Map.of(), history)
      );
      assertEquals(0, HistoryTestAccess.recoveryCaptureCount(history, owner));
      assertTrue(server.heldBy(DIMENSION, owner));
   }

   private WorldTaskContext context(UUID owner) {
      return new WorldTaskContext(null, owner, () -> {}, new RecoveryWriteGate(), server);
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }
}
