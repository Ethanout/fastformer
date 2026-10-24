package io.github.fastformer.fastplace.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VanillaBlockHistoryGameTests {
   private VanillaBlockHistoryGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_off")
   public static void defaultOffExcludesVanillaAndForcedClicks(GameTestHelper helper) {
      var player = player(helper, false);
      var floor = floor(helper);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      helper.assertTrue(player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit(floor)))
         .consumesAction(), "vanilla placement failed");
      helper.assertTrue(player.gameMode.destroyBlock(floor.above()), "vanilla break failed");
      var settings = FastPlaceSettings.load(player);
      settings.setForcePlacement(player, true);
      helper.assertTrue(player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit(floor))
         .consumesAction(), "forced placement failed");
      helper.assertTrue(helper.getLevel().getBlockState(floor.above()).is(Blocks.STONE), "ordinary click changed behavior");
      helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).isEmpty(), "disabled feature recorded history");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_door", timeoutTicks = 6000)
   public static void doorPlacementReplaysBothHalvesAsOneAction(GameTestHelper helper) {
      var player = player(helper, true);
      var floor = floor(helper);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_DOOR));
      helper.assertTrue(player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit(floor))
         .consumesAction(), "vanilla door placement failed");
      helper.assertTrue(helper.getLevel().getBlockState(floor.above()).is(Blocks.OAK_DOOR)
         && helper.getLevel().getBlockState(floor.above(2)).is(Blocks.OAK_DOOR), "door companion was not placed");
      helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 1, "door was recorded more than once");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "door undo refused");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         tick(helper, player);
         if (phase[0] == 0) {
            helper.assertTrue(helper.getLevel().getBlockState(floor.above()).isAir()
               && helper.getLevel().getBlockState(floor.above(2)).isAir(), "undo left a door half");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 1), "door redo refused");
            phase[0] = 1;
         }
         tick(helper, player);
         helper.assertTrue(helper.getLevel().getBlockState(floor.above()).is(Blocks.OAK_DOOR)
            && helper.getLevel().getBlockState(floor.above(2)).is(Blocks.OAK_DOOR), "redo lost a door half");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_break", timeoutTicks = 6000)
   public static void breakingRestoresBlockEntityContents(GameTestHelper helper) {
      var player = player(helper, true);
      var pos = floor(helper).above();
      helper.getLevel().setBlock(pos, Blocks.CHEST.defaultBlockState(), 2);
      ((ChestBlockEntity)helper.getLevel().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
      helper.assertTrue(player.gameMode.destroyBlock(pos), "chest break failed");
      helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "chest was not broken");
      helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 1, "break history missing");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "break undo refused");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         tick(helper, player);
         if (phase[0] == 0) {
            var chest = helper.getLevel().getBlockEntity(pos);
            helper.assertTrue(chest instanceof ChestBlockEntity && ((ChestBlockEntity)chest).getItem(0).is(Items.DIAMOND)
               && ((ChestBlockEntity)chest).getItem(0).getCount() == 3, "undo lost container contents");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 1), "break redo refused");
            phase[0] = 1;
         }
         tick(helper, player);
         helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "redo did not break chest");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_lily_pad", timeoutTicks = 6000)
   public static void waterSurfacePlacementIsRecorded(GameTestHelper helper) {
      var player = player(helper, true);
      var water = floor(helper).above();
      helper.getLevel().setBlock(water, Blocks.WATER.defaultBlockState(), 2);
      player.setPos(water.getCenter().add(0, 2, 0));
      player.setXRot(90);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LILY_PAD));
      var result = player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
      helper.assertTrue(result.consumesAction(), "lily pad placement failed");
      helper.assertTrue(helper.getLevel().getBlockState(water.above()).is(Blocks.LILY_PAD), "lily pad was not placed");
      helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 1, "water-surface placement was lost");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "lily pad undo refused");
      helper.succeedWhen(() -> {
         tick(helper, player);
         helper.assertTrue(helper.getLevel().getBlockState(water.above()).isAir(), "lily pad undo failed");
         helper.assertTrue(helper.getLevel().getBlockState(water).is(Blocks.WATER), "lily pad undo changed water");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_cancel")
   public static void cancelledPlacementAndUnchangedBreakLeaveNoHistory(GameTestHelper helper) {
      var player = player(helper, true);
      var floor = floor(helper);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      java.util.function.Consumer<BlockEvent.EntityPlaceEvent> cancel = event -> {
         if (event.getEntity() == player) event.setCanceled(true);
      };
      NeoForge.EVENT_BUS.addListener(cancel);
      try {
         var result = player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit(floor));
         helper.assertTrue(!result.consumesAction(), "cancelled placement succeeded");
      } finally {
         NeoForge.EVENT_BUS.unregister(cancel);
      }
      player.gameMode.destroyBlock(floor.above());
      helper.assertTrue(helper.getLevel().getBlockState(floor.above()).isAir(), "cancelled placement was not restored");
      helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).isEmpty(), "no-op left history");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_place_compaction", timeoutTicks = 6000)
   public static void placementUsesExistingStepCompression(GameTestHelper helper) { compressedRun(helper, false); }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_break_compaction", timeoutTicks = 6000)
   public static void breakingUsesExistingStepCompression(GameTestHelper helper) { compressedRun(helper, true); }

   @GameTest(template = "fastformergametests.empty", batch = "vanilla_history_redo_compaction", timeoutTicks = 6000)
   public static void finishingRedoRestartsStepCompression(GameTestHelper helper) {
      var player = player(helper, true);
      var positions = new ArrayList<BlockPos>();
      for (int index = 0; index < 14; index++) {
         var pos = helper.absolutePos(new BlockPos(1 + index % 3, 2, 1 + index / 3));
         helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
         positions.add(pos);
         helper.assertTrue(player.gameMode.destroyBlock(pos), "break failed");
      }
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "initial undo refused");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         tick(helper, player);
         if (phase[0] == 0) {
            helper.assertTrue(helper.getLevel().getBlockState(positions.getLast()).is(Blocks.STONE), "undo incomplete");
            tick(helper, player);
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 1), "redo refused");
            phase[0] = 1;
         }
         tick(helper, player);
         if (phase[0] == 1) {
            helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 7,
               "compression did not resume after redo");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 7), "compressed undo refused");
            phase[0] = 2;
         }
         tick(helper, player);
         for (var pos : positions) helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE), "group undo lost a block");
      });
   }

   private static void compressedRun(GameTestHelper helper, boolean breaking) {
      var player = player(helper, true);
      var level = helper.getLevel();
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      var positions = new ArrayList<BlockPos>();
      var states = new LinkedHashMap<BlockPos, BlockState>();
      for (int index = 0; index < 14; index++) {
         int row = index / 3;
         int column = row % 2 == 0 ? index % 3 : 2 - index % 3;
         var pos = helper.absolutePos(new BlockPos(1 + column * 2, 2, 1 + row));
         var face = index % 2 == 0 ? Direction.UP : Direction.DOWN;
         var floor = pos.relative(face.getOpposite());
         var block = index % 2 == 0 ? Blocks.STONE : Blocks.GOLD_BLOCK;
         level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
         level.setBlock(pos, breaking ? block.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
         states.put(pos, block.defaultBlockState());
         positions.add(pos);
         player.setYRot(index * 90);
         player.setXRot(index % 2 == 0 ? 60 : -60);
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(block.asItem()));
         if (breaking) helper.assertTrue(player.gameMode.destroyBlock(pos), "break failed");
         else helper.assertTrue(player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
            new BlockHitResult(floor.getCenter().add(net.minecraft.world.phys.Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, floor, false)))
            .consumesAction(), "placement failed");
      }
      var originalIds = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
      helper.assertTrue(originalIds.size() == 14, "clicks were lost or recorded twice");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         tick(helper, player);
         if (phase[0] == 0) {
            var ids = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
            helper.assertTrue(ids.size() == 7, "waiting for eight old clicks to merge");
            helper.assertTrue(ids.subList(0, 6).equals(originalIds.subList(0, 6)), "recent history was changed");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 7), "compressed undo refused");
            phase[0] = 1;
         }
         tick(helper, player);
         if (phase[0] == 1) {
            for (BlockPos pos : positions) helper.assertTrue(breaking ? level.getBlockState(pos).equals(states.get(pos))
               : level.getBlockState(pos).isAir(), "group undo lost an edit");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 7), "compressed redo refused");
            phase[0] = 2;
         }
         tick(helper, player);
         for (BlockPos pos : positions) helper.assertTrue(breaking ? level.getBlockState(pos).isAir()
            : level.getBlockState(pos).equals(states.get(pos)), "group redo lost an edit");
      });
   }

   private static ServerPlayer player(GameTestHelper helper, boolean enabled) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var settings = FastPlaceSettings.load(player);
      settings.setForcePlacement(player, false);
      if (enabled) settings.toggleVanillaBlockHistory(player);
      player.setPos(helper.absolutePos(new BlockPos(2, 3, 2)).getCenter());
      return player;
   }

   private static BlockPos floor(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 1, 2));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
      helper.getLevel().setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 2);
      helper.getLevel().setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 2);
      return pos;
   }

   private static BlockHitResult hit(BlockPos floor) {
      return new BlockHitResult(floor.getCenter().add(0, 0.5, 0), Direction.UP, floor, false);
   }

   private static void tick(GameTestHelper helper, ServerPlayer player) {
      java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
      WorldHistoryManager.tickWorld(helper.getLevel().getServer());
      helper.assertTrue(!WorldHistoryManager.busy(player), "waiting for history replay");
   }
}
