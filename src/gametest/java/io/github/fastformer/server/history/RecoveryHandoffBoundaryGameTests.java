package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;

import io.github.fastformer.server.history.entry.EntryCodecRegistry;

import io.github.fastformer.server.history.entry.HistoryEntryFormat;

import io.github.fastformer.core.gate.Gate;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A failed progress notification cannot change an accepted recovery hand-off. */
@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecoveryHandoffBoundaryGameTests {
   private static final String BATCH = "recovery_handoff_boundary";

   private RecoveryHandoffBoundaryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", batch = BATCH, timeoutTicks = 100)
   public static void aFailedNotificationCannotTurnAnAcceptedSnapshotIntoAFailure(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      UUID owner = UUID.randomUUID();
      var gate = Gate.<net.minecraft.server.level.ServerPlayer>require("available", ignored -> true);
      HistoryService history = new HistoryService(gate, gate, DiskHistoryRepository.open(level.getServer(), new HistoryEntryFormat<>(EntryCodecRegistry.standard(), HistoryEntry.class)),
         (context, key) -> { throw new IllegalStateException("notification unavailable"); });
      CompletableFuture<Void> readyForRecovery = new CompletableFuture<>();
      WorldRecoverySnapshot recovery = alreadyRestoredSnapshot(helper, level, readyForRecovery);

      try {
         TaskCancellationResult result = history.acceptTransferredRecovery(
            new WorldTaskContext(level.getServer(), owner),
            level.dimension(),
            recovery,
            null,
            () -> {},
            () -> {}
         );

         // The notification throws inside the call. The queue already owns the capture, so
         // the call must still report the accepted result and must not throw.
         helper.assertTrue(
            result == TaskCancellationResult.ROLLBACK_STARTED,
            "a failed notification changed the accepted result to " + result
         );
         helper.assertTrue(
            HistoryTestAccess.recoveryCaptureCount(history, owner) == 1,
            "the queue did not hold the accepted snapshot exactly once"
         );
      } finally {
         // The capture must not outlive this test, and this runs even when an assertion above
         // fails. Completing the signal lets the owner resolve the capture on the next server
         // tick: the world already matches the before state, so recovery resolves it as
         // already restored without writing a block.
         readyForRecovery.complete(null);
      }

      helper.succeedWhen(() -> {
         history.tickWorld(level.getServer());
         helper.assertTrue(HistoryTestAccess.recoveryCaptureCount(history, owner) == 0,
            "waiting for the queued recovery capture to resolve");
         history.close();
      });
   }

   /**
    * A snapshot whose before and after sides are equal captures of a real block. The live
    * world already matches it, so recovery resolves the capture without a world write.
    */
   private static WorldRecoverySnapshot alreadyRestoredSnapshot(
      GameTestHelper helper, ServerLevel level, CompletableFuture<Void> readyForRecovery
   ) {
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      ReversibleBlockSnapshot before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      ReversibleBlockSnapshot after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      return new WorldRecoverySnapshot(
         new ArrayDeque<>(List.of(before)), Map.of(pos, after), readyForRecovery
      );
   }

}
