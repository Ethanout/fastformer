package io.github.fastformer.server.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryEntryFormat;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.testkit.CounterHistory;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MixedEntryReplayGameTests {
   private MixedEntryReplayGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "mixed_entry_replay", timeoutTicks = 2000)
   public static void mixedEntriesUndoAndRedoThroughTheirOwnReplayAndJournal(GameTestHelper helper) {
      var level = helper.getLevel();
      var counter = new CounterHistory();
      var history = service(helper, counter);
      var player = helper.makeMockServerPlayerInLevel();
      var pos = helper.absolutePos(new BlockPos(1, 2, 1));
      level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var block = WorldChangeBatch.capturePairsByPos(level, List.of(before),
         Map.of(pos, ReversibleBlockSnapshot.capture(level, pos).orElseThrow())).orElseThrow();
      history.recordEntry(player, block);
      counter.value.set(2);
      history.recordEntry(player, counter.entry(0, 2));
      helper.assertTrue(history.requestUndo(player, 2), "Mixed undo refused");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         history.tickWorld(level.getServer());
         helper.assertTrue(!history.busy(player), "Mixed replay is still pending");
         if (phase[0] == 0) {
            helper.assertTrue(counter.value.get() == 0, "Custom entry was not undone");
            helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Block entry was not undone");
            helper.assertTrue(history.requestRedo(player, 2), "Mixed redo refused");
            phase[0] = 1;
            helper.assertTrue(false, "Waiting for redo");
         }
         helper.assertTrue(counter.value.get() == 2, "Custom entry was not redone");
         helper.assertTrue(level.getBlockState(pos).is(Blocks.GOLD_BLOCK), "Block entry was not redone");
         helper.assertTrue(counter.events.equals(List.of("prepare", "complete", "prepare", "complete")),
            "Custom journal lifecycle was bypassed: " + counter.events);
         helper.assertTrue(!WorldWriteCoordinator.heldBy(level.getServer(), level.dimension(), player.getUUID()), "Lease leaked");
         history.close();
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "mixed_entry_replay_failure", timeoutTicks = 1000)
   public static void failedCustomApplyUsesItsOwnPartialStateAndRollback(GameTestHelper helper) {
      var level = helper.getLevel();
      var counter = new CounterHistory();
      var history = service(helper, counter);
      var player = helper.makeMockServerPlayerInLevel();
      var entry = counter.entry(0, 2);
      counter.value.set(2);
      history.recordEntry(player, entry);
      counter.failNextApply = true;
      helper.assertTrue(history.requestUndo(player, 1), "Custom undo refused");
      helper.succeedWhen(() -> {
         history.tickWorld(level.getServer());
         helper.assertTrue(!history.busy(player), "Failed custom replay is still pending");
         helper.assertTrue(counter.value.get() == 2, "Partial custom write was not rolled back");
         helper.assertTrue(counter.events.equals(List.of("prepare", "resolve")), "Wrong rollback journal lifecycle: " + counter.events);
         helper.assertTrue(HistoryTestAccess.historyOrder(history, player.getUUID(), true).equals(List.of(entry.operationId())),
            "Failed replay committed the history order");
         helper.assertTrue(!WorldWriteCoordinator.heldBy(level.getServer(), level.dimension(), player.getUUID()), "Lease leaked");
         history.close();
      });
   }

   private static HistoryService service(GameTestHelper helper, CounterHistory counter) {
      Gate<ServerPlayer> gate = Gate.require("test", ignored -> true);
      var repository = DiskHistoryRepository.open(helper.getLevel().getServer(),
         new HistoryEntryFormat<>(counter.codecs(), HistoryEntry.class));
      return new HistoryService(gate, gate, repository, counter.replays());
   }
}
