package io.github.fastformer.server.intent;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.protocol.settings.PlacementEffectValue;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SettingsIntentGameTests {
   private SettingsIntentGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "settings_intents", timeoutTicks = 600)
   public static void settingWritesAreIdempotentAndShareServerLimits(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      var request = SettingsKeys.VANILLA_BLOCK_HISTORY.set(true);
      helper.assertTrue(PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, request).handled(), "network setting refused");
      helper.assertTrue(PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, request).handled(), "command setting refused");
      var settings = FastPlaceSettings.load(player);
      helper.assertTrue(settings.vanillaBlockHistory(), "repeated true write toggled the setting off");
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.WORLD_HISTORY_LIMIT.set(Integer.MAX_VALUE));
      PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, SettingsKeys.SESSION_HISTORY_LIMIT.set(Integer.MIN_VALUE));
      settings = FastPlaceSettings.load(player);
      helper.assertTrue(settings.worldUndoHistoryLimit() == 800 && settings.sessionUndoHistoryLimit() == 1,
         "setting path bypassed existing history clamps");
      PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.RECENT_GROUP.set(0));
      helper.assertTrue(FastPlaceSettings.load(player).tinkerHistoryMergeLimits().recentGroup() == 1, "compression limit was not clamped");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "settings_intents", timeoutTicks = 600)
   public static void unsupportedQueriesAndUnknownEffectsCannotMutateSettings(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      var settings = FastPlaceSettings.load(player);
      boolean before = settings.middleConfirmEnabled();
      helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.MIDDLE_CONFIRM.query()).handled(),
         "unsupported query should pass without writing a value");
      helper.assertTrue(before == FastPlaceSettings.load(player).middleConfirmEnabled(), "query changed middle confirm");
      helper.assertFalse(PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.PLACEMENT_EFFECT.set(
         new PlacementEffectValue(ResourceLocation.parse("test:missing"), true))).handled(), "unknown effect was accepted");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      ServerPlayer player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      return player;
   }
}
