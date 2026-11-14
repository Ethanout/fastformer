package io.github.fastformer.server.settings;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SettingsPermissionGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "network_falling_permissions", timeoutTicks = 600)
   public static void fallingRuleRequiresOperatorAndUsesExplicitTarget(GameTestHelper helper) throws Exception {
      ServerPlayer player = io.github.fastformer.testkit.ServerPlayers.connect(helper, "setting-test");
      var server = player.getServer();
      var rules = io.github.fastformer.server.worldwrite.effect.BlockActivityRules.get(server);
      boolean original = rules.fallingDisabled();
      boolean originallyFrozen = server.tickRateManager().isFrozen();
      boolean operator = server.getPlayerList().isOp(player.getGameProfile());
      try {
         server.getPlayerList().deop(player.getGameProfile());
         helper.assertFalse(player.hasPermissions(2), "test player still has operator permission");
         rules.setFallingDisabled(false);
         for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
            player.setGameMode(mode);
            PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.FALLING_DISABLED.set(true));
            helper.assertFalse(rules.fallingDisabled(), "unprivileged player changed the falling rule in " + mode);
         }
         server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
         helper.assertTrue(player.hasPermissions(2), "test operator lacks permission");
         PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.FALLING_DISABLED.set(true));
         helper.assertTrue(rules.fallingDisabled(), "operator could not disable falling");
         PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.FALLING_DISABLED.set(true));
         helper.assertTrue(rules.fallingDisabled(), "replayed explicit target toggled the state");
         PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, SettingsKeys.FALLING_DISABLED.set(false));
         helper.assertFalse(rules.fallingDisabled(), "operator could not enable falling");
         helper.assertTrue(server.tickRateManager().isFrozen() == originallyFrozen, "falling rule changed tick freeze");
      } finally {
         rules.setFallingDisabled(original);
         if (!operator) server.getPlayerList().deop(player.getGameProfile());
      }
      helper.succeed();
   }

}
