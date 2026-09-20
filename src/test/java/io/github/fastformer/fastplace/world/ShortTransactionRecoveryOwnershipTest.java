package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      Registries.DIMENSION,
      ResourceLocation.fromNamespaceAndPath("fastformer", "short_transaction_ownership_test")
   );

   @AfterEach
   void clearState() {
      WorldHistoryManager.clearServer();
      WorldWriteCoordinator.clearAll();
   }

   @Test
   void anAcceptedHandOffGivesTheCaptureToTheRecoveryQueueAndKeepsTheLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, DIMENSION, owner);
      assertNotNull(lease);
      BlockPos pos = new BlockPos(3, 4, 5);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));

      // An incomplete pair set stays queued, so the recovery queue owns it.
      ShortWriteTransaction.Outcome outcome = ShortWriteTransaction.handOffRecovery(
         new WorldTaskContext(null, owner), DIMENSION, before, Map.of()
      );

      assertEquals(ShortWriteTransaction.Outcome.RECOVERY_PENDING, outcome);
      assertEquals(1, WorldHistoryManager.recoveryCaptureCountForTest(owner));
      assertTrue(WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      assertTrue(outcome.consumedInteraction());
   }

   @Test
   void aRefusedHandOffReportsABlockedRecoveryAndNeverClaimsARecovery() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, DIMENSION, owner);
      assertNotNull(lease);
      BlockPos pos = new BlockPos(6, 7, 8);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));

      // A capture without a dimension has no recovery owner.
      ShortWriteTransaction.Outcome outcome = ShortWriteTransaction.handOffRecovery(
         new WorldTaskContext(null, owner), null, before, Map.of()
      );

      assertNotEquals(ShortWriteTransaction.Outcome.RECOVERY_PENDING, outcome);
      assertEquals(ShortWriteTransaction.Outcome.RECOVERY_BLOCKED, outcome);
      assertEquals(0, WorldHistoryManager.recoveryCaptureCountForTest(owner));
      // The write lock and the capture stay with the caller, so another writer
      // cannot enter the half-written world.
      assertTrue(WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      assertEquals(1, before.size());
      assertNotNull(before.peekFirst());
      assertTrue(outcome.consumedInteraction());
   }

   @Test
   void anEmptyOrMissingCaptureIsNotHandedOff() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      WorldWriteCoordinator.Lease lease = WorldWriteCoordinator.acquire(server, DIMENSION, owner);
      assertNotNull(lease);

      // No capture means no recovery work. The lease stays with the caller
      // because the transaction never released it.
      assertEquals(
         ShortWriteTransaction.Outcome.RECOVERY_BLOCKED,
         ShortWriteTransaction.handOffRecovery(new WorldTaskContext(null, owner), DIMENSION, new ArrayDeque<>(), Map.of())
      );
      assertEquals(
         ShortWriteTransaction.Outcome.RECOVERY_BLOCKED,
         ShortWriteTransaction.handOffRecovery(new WorldTaskContext(null, owner), DIMENSION, null, Map.of())
      );
      assertEquals(0, WorldHistoryManager.recoveryCaptureCountForTest(owner));
      assertTrue(WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }
}
