package io.github.fastformer.server.intent;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.session.QuickShapeSystem;
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
public final class SessionIntentGameTests {
   private SessionIntentGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "session_intents", timeoutTicks = 600)
   public static void commandAndNetworkCycleUseTheSameIdleAction(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      var settings = FastPlaceSettings.load(player);
      settings.setMode(player, LineMode.AXIS);
      helper.assertTrue(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SessionIntents.CycleMode(null)).handled(),
         "command cycle was not handled");
      LineMode commandMode = FastPlaceSettings.load(player).lineMode();
      FastPlaceSettings.load(player).setMode(player, LineMode.AXIS);
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SessionIntents.CycleMode(null));
      helper.assertTrue(FastPlaceSettings.load(player).lineMode() == commandMode, "network cycle differs from command cycle");
      helper.assertFalse(QuickShapeSystem.of(player).active(player), "idle cycle created a draft");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "session_intents", timeoutTicks = 600)
   public static void commandAndNetworkAdjustShareClampingAndDraftState(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      FastPlaceSettings.load(player).setMode(player, LineMode.FREE_SCROLL);
      BlockPos first = helper.absolutePos(new BlockPos(2, 4, 2));
      QuickShapeSystem.of(player).addPoint(player, first, first);
      helper.assertTrue(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SessionIntents.Adjust(1000)).handled(),
         "command adjust was not handled");
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      helper.assertTrue(!draft.freeScrollOffset().equals(BlockPos.ZERO), "command did not adjust the draft");
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SessionIntents.Adjust(-1000));
      helper.assertTrue(draft.freeScrollOffset().equals(BlockPos.ZERO), "command and network used different clamping");
      QuickShapeSystem.of(player).cancel(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "session_intents", timeoutTicks = 600)
   public static void deniedEditingStillAllowsNetworkModifierRelease(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SessionIntents.Submode(true));
      helper.assertTrue(QuickShapeSystem.of(player).modifierHeld(player), "modifier was not held");
      LineMode mode = FastPlaceSettings.load(player).lineMode();
      FastPlaceSettings.load(player).toggleEnabled(player);
      helper.assertFalse(FastPlaceSettings.load(player).enabled(), "test did not disable editing");
      var cycle = PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SessionIntents.CycleMode(null));
      helper.assertTrue(cycle.status() == IntentResult.Status.DENIED, "disabled editing passed admission");
      helper.assertTrue(FastPlaceSettings.load(player).lineMode() == mode, "refused cycle changed the mode");
      helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SessionIntents.Submode(false)).handled(),
         "unavailable command changed the modifier");
      helper.assertTrue(QuickShapeSystem.of(player).modifierHeld(player), "command release changed the existing latch");
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, new SessionIntents.Submode(false));
      helper.assertFalse(QuickShapeSystem.of(player).modifierHeld(player), "network release left the modifier latched");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
      player.setPos(helper.absolutePos(new BlockPos(2, 5, 2)).getCenter());
      player.setXRot(0);
      player.setYRot(0);
      return player;
   }
}
