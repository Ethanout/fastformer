package io.github.fastformer.server.intent.quickshape;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.testkit.ServerPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuickShapeIntentGameTests {
   private QuickShapeIntentGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_intents", timeoutTicks = 600)
   public static void commandAndDirectEditsShareStageRules(GameTestHelper helper) throws Exception {
      ServerPlayer player = player(helper);
      try {
         var commands = player.getServer().getCommands().getDispatcher();
         var source = player.createCommandSourceStack();
         helper.assertTrue(commands.execute("ff line mode free_scroll", source) == 1, "line mode command refused");
         var shape = QuickShapeSystem.of(player);
         BlockPos first = helper.absolutePos(new BlockPos(2, 3, 2));
         shape.addPoint(player, first, first);
         var draft = shape.session(player).orElseThrow();
         helper.assertTrue(commands.execute("ff line free_distance 8", source) == 1, "line distance command refused");
         helper.assertTrue(draft.freeScrollOffset().equals(new BlockPos(8, 0, 0)), "line distance lost");
         helper.assertTrue(dispatch(player, new QuickShapeIntents.ContextValue(11)), "context edit refused");
         helper.assertTrue(draft.freeScrollOffset().equals(new BlockPos(11, 0, 0)), "context edit used a different stage");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.ContextValue(Double.NaN)), "non-finite context accepted");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.LineDistance(0)), "line lower limit bypassed");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.LineDistance(129)), "line upper limit bypassed");
         helper.assertTrue(draft.freeScrollOffset().equals(new BlockPos(11, 0, 0)), "rejected input changed offset");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.FaceAngle(45)), "line accepted a polygon angle");

         dispatch(player, new QuickShapeIntents.SelectStageMode(FaceMode.POLYGON));
         draft.addPoint(first.east(3), player.getEyePosition(), player.getViewVector(1));
         helper.assertTrue(commands.execute("ff face angle 30", source) == 1, "polygon angle command refused");
         helper.assertTrue(FastPlaceSettings.load(player).angleDegrees() == 30, "polygon angle not saved");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.LineDistance(6)), "face accepted line distance");
      } finally { cleanup(player); }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_shape_intents", timeoutTicks = 600)
   public static void unavailableIntentsDoNotChangeModesOrCreateDrafts(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      try {
         helper.assertFalse(dispatch(player, new QuickShapeIntents.ContextValue(2)), "context without draft accepted");
         helper.assertFalse(dispatch(player, new QuickShapeIntents.VolumeDistance(2)), "volume without draft accepted");
         var before = FastPlaceSettings.load(player).lineMode();
         player.setGameMode(GameType.SURVIVAL);
         helper.assertFalse(dispatch(player, new QuickShapeIntents.SelectStageMode(LineMode.RAYCAST)), "survival edit accepted");
         helper.assertTrue(FastPlaceSettings.load(player).lineMode() == before, "denied edit changed preference");
         helper.assertFalse(QuickShapeSystem.of(player).active(player), "parameter edit created a draft");
      } finally { cleanup(player); }
      helper.succeed();
   }

   private static boolean dispatch(ServerPlayer player, Intent intent) {
      return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, intent).handled();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      var player = ServerPlayers.connect(helper, "shape-intent-test");
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
      return player;
   }

   private static void cleanup(ServerPlayer player) {
      SessionSystem.of(player).discardPlayer(player);
      PlayerStateRegistry.detach(player);
      player.getServer().getPlayerList().remove(player);
   }
}
