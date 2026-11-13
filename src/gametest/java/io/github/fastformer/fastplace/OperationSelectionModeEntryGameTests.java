package io.github.fastformer.fastplace;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.SessionIntents;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OperationSelectionModeEntryGameTests {
   private static final long HISTORY_TIMEOUT_MILLIS = 10_000L;

   private OperationSelectionModeEntryGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "operation_selection_mode", timeoutTicks = 600)
   public static void vanillaItemCallbacksCannotCreateOrChangeSelectionPoints(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);
      var point = net.minecraft.core.BlockPos.containing(player.getEyePosition().add(32, 0, 0));
      var hit = new net.minecraft.world.phys.BlockHitResult(point.getCenter(), net.minecraft.core.Direction.UP, point, false);
      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for the operation input gate");
         try {
            for (var mode : new OperationSelectionMode[]{OperationSelectionMode.CUBOID, OperationSelectionMode.SMART}) {
               FastPlaceSettings.load(player).setOperationSelectionMode(player, mode);
               for (var item : java.util.List.of(net.minecraft.world.item.Items.WATER_BUCKET,
                  net.minecraft.world.item.Items.LAVA_BUCKET, net.minecraft.world.item.Items.BUCKET,
                  net.minecraft.world.item.Items.FLINT_AND_STEEL, net.minecraft.world.item.Items.BONE_MEAL,
                  net.minecraft.world.item.Items.AIR)) {
                  player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(item));
                  helper.assertFalse(ServerInputDispatcher.rightClickBlock(player, hit), "vanilla block callback must not create a selection");
                  helper.assertFalse(ServerInputDispatcher.rightClickItem(player), "vanilla item callback must not create a selection");
                  helper.assertFalse(OperationSystem.of(player).active(player), "ordinary item use must leave the selection session idle");
               }
            }
            FastPlaceSettings.load(player).setOperationSelectionMode(player, OperationSelectionMode.CUBOID);
            OperationSystem.of(player).startFirst(player, point);
            var before = OperationSystem.of(player).session(player).orElseThrow().points();
            helper.assertFalse(ServerInputDispatcher.rightClickBlock(player, hit), "vanilla callbacks must not append remote selection points");
            helper.assertFalse(ServerInputDispatcher.rightClickItem(player), "vanilla callbacks must not complete remote selections");
            helper.assertTrue(OperationSystem.of(player).session(player).orElseThrow().points().equals(before), "remote points must retain their explicit command owner");
         } finally {
            OperationSystem.of(player).cancel(player);
         }
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "operation_selection_mode", timeoutTicks = 600)
   public static void modeCycleKeepsServerPointEntryCuboid(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, HISTORY_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for the operation input gate");
         helper.assertFalse(OperationSystem.of(player).active(player), "selection session must start empty");
         helper.assertTrue(
            FastPlaceSettings.load(player).operationSelectionMode() == OperationSelectionMode.CUBOID,
            "selection mode should begin as cuboid"
         );

         PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SessionIntents.CycleMode(null));

         helper.assertFalse(OperationSystem.of(player).active(player), "cycling mode must not create a selection session");
         helper.assertTrue(
            FastPlaceSettings.load(player).operationSelectionMode() == OperationSelectionMode.CUBOID,
            "client-owned tool cycling must not create a server smart-point session"
         );
      });
   }
}
