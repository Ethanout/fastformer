package io.github.fastformer.fastplace.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HistoryConflictGameTests {
   private HistoryConflictGameTests() { }

   @GameTest(template = "fastformergametests.empty")
   public static void overwriteJournalsAndRollsBackTheLiveConflict(GameTestHelper helper) {
      var fixture = fixture(helper);
      var resolution = new HistoryConflictResolution();
      helper.assertTrue(resolution.capture(helper.getLevel(), fixture.batch(), 0, false), "Live snapshot failed");
      helper.assertTrue(resolution.match(helper.getLevel(), fixture.batch(), 0, true) == 1, "Confirmed overwrite did not accept its source");
      var journalSource = resolution.snapshots(fixture.batch(), true, false).getFirst();
      helper.assertTrue(journalSource.state().is(Blocks.DIAMOND_BLOCK), "Journal used the stale historical source");
      fixture.batch().apply(helper.getLevel(), 0, true, 2);
      helper.assertTrue(resolution.rollbackMatch(helper.getLevel(), fixture.batch(), 0, true) == 1, "Rollback lost target ownership");
      helper.assertTrue(resolution.rollback(helper.getLevel(), fixture.batch(), 0, true, 2), "Rollback failed");
      helper.assertTrue(helper.getLevel().getBlockState(fixture.pos()).is(Blocks.DIAMOND_BLOCK), "Rollback lost the overwritten block");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void skipPreservesConflictAndRecoveryHasNoWriteForIt(GameTestHelper helper) {
      var fixture = fixture(helper);
      var resolution = new HistoryConflictResolution();
      helper.assertTrue(resolution.capture(helper.getLevel(), fixture.batch(), 0, true), "Live snapshot failed");
      helper.assertTrue(resolution.match(helper.getLevel(), fixture.batch(), 0, true) == 2, "Skipped block remained writable");
      helper.assertTrue(resolution.snapshots(fixture.batch(), true, false).getFirst().sameContents(
         resolution.snapshots(fixture.batch(), true, true).getFirst()), "Skipped block gained a recovery write");
      helper.assertTrue(helper.getLevel().getBlockState(fixture.pos()).is(Blocks.DIAMOND_BLOCK), "Skip changed the conflict");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void anotherChangeAfterConfirmationStillFailsTheWriteGuard(GameTestHelper helper) {
      var fixture = fixture(helper);
      var resolution = new HistoryConflictResolution();
      resolution.capture(helper.getLevel(), fixture.batch(), 0, false);
      helper.getLevel().setBlock(fixture.pos(), Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
      helper.assertTrue(resolution.match(helper.getLevel(), fixture.batch(), 0, true) == 0, "Confirmation authorized a later external write");
      helper.assertTrue(resolution.rollbackMatch(helper.getLevel(), fixture.batch(), 0, true) == 0, "Rollback claimed the external write");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty")
   public static void partialUndoRestoresOnlyTheNonconflictingCell(GameTestHelper helper) {
      var level = helper.getLevel();
      var first = helper.absolutePos(new BlockPos(1, 2, 1));
      var second = first.east();
      level.setBlock(first, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(second, Blocks.STONE.defaultBlockState(), 2);
      var before = List.of(ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
         ReversibleBlockSnapshot.capture(level, second).orElseThrow());
      level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      level.setBlock(second, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = Map.of(first, ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
         second, ReversibleBlockSnapshot.capture(level, second).orElseThrow());
      var batch = WorldChangeBatch.capturePairsByPos(level, before, after).orElseThrow();
      level.setBlock(first, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var resolution = new HistoryConflictResolution();
      for (int index = 0; index < batch.size(); index++) {
         if (batch.match(level, index, true) == 0) {
            helper.assertTrue(resolution.capture(level, batch, index, true), "Conflict snapshot failed");
         }
         if (resolution.match(level, batch, index, true) == 1) {
            helper.assertTrue(batch.apply(level, index, true, 2), "Nonconflicting undo failed");
         }
      }
      helper.assertTrue(level.getBlockState(first).is(Blocks.DIAMOND_BLOCK), "Partial undo changed the conflict");
      helper.assertTrue(level.getBlockState(second).is(Blocks.STONE), "Partial undo did not restore the other block");
      helper.succeed();
   }

   private static Fixture fixture(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(1, 2, 1));
      var level = helper.getLevel();
      level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      var batch = WorldChangeBatch.capturePairsByPos(level, List.of(before), Map.of(pos, after)).orElseThrow();
      level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      return new Fixture(pos, batch);
   }

   private record Fixture(BlockPos pos, WorldChangeBatch batch) { }
}
