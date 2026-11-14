package io.github.fastformer.server.command;

import com.mojang.brigadier.tree.CommandNode;
import io.github.fastformer.FastFormer;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.settings.PlayerSettingsIntents;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.github.fastformer.testkit.ServerPlayers;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CommandSystemGameTests {
   private CommandSystemGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "command_modules", timeoutTicks = 600)
   public static void aliasesKeepTheSameTreeAndStageVocabulary(GameTestHelper helper) throws Exception {
      var player = ServerPlayers.connect(helper, "command-module-test");
      try {
         HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
         player.setGameMode(GameType.CREATIVE);
         var dispatcher = player.getServer().getCommands().getDispatcher();
         var root = dispatcher.getRoot().getChild("ff");
         var alias = dispatcher.getRoot().getChild("fastformer");
         helper.assertTrue(shape(root).equals(shape(alias)), "command aliases differ");
         Set<String> expected = Set.of("value", "line", "face", "volume", "operation", "geometry",
            "action", "rotate", "placement", "reach", "forceplace", "undo", "redo", "fillmode",
            "max_placement", "undo_history", "session_undo_history", "history_cleanup", "middle_confirm",
            "settings", "toggle", "cancel", "mode", "status");
         helper.assertTrue(root.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()).equals(expected),
            "public command paths changed");
         var source = player.createCommandSourceStack();
         helper.assertTrue(dispatcher.execute("ff face mode base_plane", source) == 1, "base plane command refused");
         helper.assertTrue(FastPlaceSettings.load(player).faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE, "base plane mapped incorrectly");
         helper.assertTrue(dispatcher.execute("fastformer face mode parallelogram_base_plane", source) == 1, "documented face spelling refused");
         helper.assertTrue(dispatcher.execute("ff face mode unknown", source) == 0, "unknown mode accepted");
         helper.assertTrue(FastPlaceSettings.load(player).faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE, "invalid mode changed preference");
         for (String invalid : new String[]{"ff line free_distance 129", "ff geometry ellipse 1 9", "ff rotate euler 1 2"}) {
            boolean refused = false;
            try { dispatcher.execute(invalid, source); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expectedFailure) { refused = true; }
            helper.assertTrue(refused, "invalid command accepted: " + invalid);
         }
      } finally {
         SessionSystem.of(player).discardPlayer(player);
         PlayerStateRegistry.detach(player);
         player.getServer().getPlayerList().remove(player);
      }
      helper.succeed();
   }

   private static String shape(CommandNode<CommandSourceStack> node) {
      return node.getChildren().stream().sorted(java.util.Comparator.comparing(CommandNode::getName))
         .map(child -> child.getName() + ":" + child.getClass().getSimpleName() + ":"
            + (child.getCommand() != null) + "[" + shape(child) + "]")
         .collect(Collectors.joining(","));
   }

   @GameTest(template = "fastformergametests.empty", batch = "command_modules", timeoutTicks = 600)
   public static void settingCommandsRetainQueriesAdmissionAndCleanup(GameTestHelper helper) throws Exception {
      var player = ServerPlayers.connect(helper, "setting-command-test");
      try {
         HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
         player.setGameMode(GameType.CREATIVE);
         var commands = player.getServer().getCommands().getDispatcher();
         var source = player.createCommandSourceStack();
         helper.assertTrue(commands.execute("ff middle_confirm false", source) == 1, "middle confirm command refused");
         helper.assertTrue(commands.execute("ff middle_confirm", source) == 1, "middle confirm query refused");
         helper.assertFalse(FastPlaceSettings.load(player).middleConfirmEnabled(), "query changed setting");
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.MIDDLE_CONFIRM.query()).handled(),
            "network query unexpectedly became a command query");
         helper.assertTrue(commands.execute("ff undo_history 7", source) == 1, "history limit command refused");
         helper.assertTrue(FastPlaceSettings.load(player).worldUndoHistoryLimit() == 7, "history limit changed");
         OperationSystem.of(player).startFirst(player, BlockPos.ZERO);
         helper.assertTrue(commands.execute("ff toggle", source) == 1, "disable command refused");
         helper.assertFalse(FastPlaceSettings.load(player).enabled() || OperationSystem.of(player).active(player), "disable left editor active");
         helper.assertTrue(commands.execute("ff undo_history 8", source) == 0, "disabled history command admitted");
         helper.assertTrue(FastPlaceSettings.load(player).worldUndoHistoryLimit() == 7, "refused history command wrote a value");
         helper.assertTrue(commands.execute("ff middle_confirm true", source) == 1, "available settings require enabled editing");
         helper.assertTrue(commands.execute("ff fillmode 1", source) == 1, "fill mode numeric spelling refused");
         helper.assertTrue(commands.execute("ff max_placement 23", source) == 1, "max placement command refused");
         helper.assertTrue(FastPlaceSettings.load(player).maxPlacement() == 23, "max placement value lost");
         helper.assertTrue(commands.execute("ff toggle", source) == 1, "enable command refused");
         player.setGameMode(GameType.SURVIVAL);
         helper.assertTrue(commands.execute("ff undo_history 9", source) == 0, "survival history command admitted");
         helper.assertTrue(commands.execute("ff placement conflict keep_existing", source) == 1, "setting command gained creative gate");
         helper.assertTrue(commands.execute("ff status", source) == 1, "status command refused");
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new PlayerSettingsIntents.OpenScreen()).handled(),
            "screen request succeeded without client channel");
      } finally {
         SessionSystem.of(player).discardPlayer(player);
         PlayerStateRegistry.detach(player);
         player.getServer().getPlayerList().remove(player);
      }
      helper.succeed();
   }
}
