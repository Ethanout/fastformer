package io.github.fastformer.server.intent.selection;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.testkit.ServerPlayers;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SelectionIntentGameTests {
   private SelectionIntentGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "selection_intents", timeoutTicks = 600)
   public static void commandEditsRetainSelectionRulesAndNetworkRequiresConfirmation(GameTestHelper helper) throws Exception {
      var player = ServerPlayers.connect(helper, "selection-intent-test");
      var history = HistorySystem.of(player);
      HistoryTestAccess.awaitInitialHistoryLoad(history, player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      var runtime = SessionSystem.of(player);
      try {
         var commands = player.getServer().getCommands().getDispatcher();
         var source = player.createCommandSourceStack();
         var selection = OperationSystem.of(player);
         var first = helper.absolutePos(new BlockPos(2, 3, 2));
         var session = selection.startFirst(player, first);
         helper.assertTrue(commands.execute("ff operation selection cuboid", source) == 1, "selection mode command refused");
         helper.assertTrue(commands.execute("ff operation conflict keep_existing", source) == 1, "conflict command refused");
         helper.assertTrue(session.conflictMode() == OperationConflictMode.KEEP_EXISTING, "conflict mode lost");
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
            new SelectionIntents.SelectMode(OperationSelectionMode.CONVEX_HULL)).handled(), "retired hull mode accepted");
         selection.setSecond(player, first.east());
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SelectionIntents.Apply(true)).handled(),
            "network submitted unconfirmed selection");
         helper.assertFalse(runtime.operationTasks().active(player), "refused submission queued a writer");
         helper.assertTrue(commands.execute("ff operation copy", source) == 1, "command direct submission changed");
         helper.assertTrue(runtime.operationTasks().active(player), "command did not queue operation");
         helper.assertFalse(selection.active(player), "submitted selection remained active");
      } finally {
         runtime.operationTasks().cancel(player);
         runtime.discardPlayer(player);
         PlayerStateRegistry.detach(player);
         player.getServer().getPlayerList().remove(player);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "selection_intents", timeoutTicks = 600)
   public static void editIntentsRetainPointRulesAndRejectUnavailableActors(GameTestHelper helper) {
      var player = ServerPlayers.connect(helper, "selection-edit-test");
      var history = HistorySystem.of(player);
      HistoryTestAccess.awaitInitialHistoryLoad(history, player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      var runtime = SessionSystem.of(player);
      try {
         var selection = OperationSystem.of(player);
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL, new SelectionIntents.SelectPoint(0)).handled(),
            "point edit created a missing selection");
         var first = helper.absolutePos(new BlockPos(2, 3, 2));
         var session = selection.startFirst(player, first);
         selection.setSelectionMode(player, OperationSelectionMode.PRISM);
         selection.startFirst(player, first);
         selection.setSecond(player, first.east(2));
         helper.assertTrue(PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL,
            new SelectionIntents.Expand(first.south(2))).handled(), "prism expansion refused");
         helper.assertTrue(session.points().size() == 3, "prism expansion did not add a point");
         PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL, new SelectionIntents.SelectPoint(1));
         helper.assertTrue(session.selectedPointIndex() == 1, "point selection lost");
         var target = first.east(3);
         PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL,
            new SelectionIntents.DragPoint(1, target, OperationPointDragConstraint.FREE, true));
         helper.assertTrue(session.points().get(1).equals(target), "point drag target lost");
         var before = session.points();
         player.setGameMode(GameType.SURVIVAL);
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL,
            new SelectionIntents.RemovePoint(1)).handled(), "survival edit accepted");
         helper.assertTrue(session.points().equals(before), "refused edit changed points");
         player.setGameMode(GameType.CREATIVE);
         PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL, new SelectionIntents.RemovePoint(2));
         helper.assertTrue(session.points().size() == 2, "point removal failed");
         helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL,
            new SelectionIntents.Extend(9, true, 1, true)).handled(), "invalid extension axis accepted");
      } finally {
         runtime.discardPlayer(player);
         PlayerStateRegistry.detach(player);
         player.getServer().getPlayerList().remove(player);
      }
      helper.succeed();
   }
}
