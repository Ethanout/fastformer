package io.github.fastformer.server.command;

import com.mojang.brigadier.tree.CommandNode;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.testkit.ServerPlayers;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
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
}
