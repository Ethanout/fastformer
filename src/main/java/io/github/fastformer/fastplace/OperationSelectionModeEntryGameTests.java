package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
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
   public static void shortModifierCyclesSelectionModeBeforeTheFirstPoint(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, HISTORY_TIMEOUT_MILLIS);
      player.setGameMode(GameType.CREATIVE);

      helper.succeedWhen(() -> {
         helper.assertTrue(ServerInputDispatcher.canOperate(player), "waiting for the operation input gate");
         helper.assertFalse(OperationManager.active(player), "selection session must start empty");
         helper.assertTrue(
            FastPlaceSettings.load(player).operationSelectionMode() == OperationSelectionMode.CUBOID,
            "selection mode should begin as cuboid"
         );

         ServerInputDispatcher.shortModifier(player);

         helper.assertFalse(OperationManager.active(player), "cycling mode must not create a selection session");
         helper.assertTrue(
            FastPlaceSettings.load(player).operationSelectionMode() == OperationSelectionMode.PRISM,
            "short modifier should select prism for the next selection"
         );
      });
   }
}
