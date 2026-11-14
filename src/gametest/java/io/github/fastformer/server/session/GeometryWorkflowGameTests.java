package io.github.fastformer.server.session;

import io.github.fastformer.FastFormer;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.testkit.ServerPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeometryWorkflowGameTests {
   private GeometryWorkflowGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_workflow", timeoutTicks = 600)
   public static void coneWorkflowRestoresAndPersistsItsOwnPlanePreference(GameTestHelper helper) {
      var player = ServerPlayers.connect(helper, "geometry-workflow-test");
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      try {
         FastPlaceSettings.load(player).setConePlaneMode(player, ConePlaneMode.DIAMETER);
         var geometry = GeometrySystem.of(player);
         geometry.selectMode(player, GeometryMode.CONE_PRISM);
         var draft = geometry.session(player).orElseThrow();
         var originalId = draft.draftId();
         helper.assertTrue(draft.cone().conePlaneMode() == ConePlaneMode.DIAMETER,
            "opening a cone did not restore the saved plane preference");

         helper.assertTrue(geometry.cycleMode(player), "cone plane cycle was refused");
         helper.assertTrue(FastPlaceSettings.load(player).conePlaneMode() == ConePlaneMode.THREE_POINT,
            "cone workflow did not persist its cycled plane preference");
         geometry.cancel(player);
         geometry.selectMode(player, GeometryMode.CONE_PRISM);
         draft = geometry.session(player).orElseThrow();
         helper.assertTrue(draft.cone().conePlaneMode() == ConePlaneMode.THREE_POINT,
            "reopening a cone lost the cycled preference");
         helper.assertTrue(!draft.draftId().equals(originalId), "reopening retained the old draft identity");
      } finally {
         SessionSystem.of(player).discardPlayer(player);
         PlayerStateRegistry.detach(player);
         player.getServer().getPlayerList().remove(player);
      }
      helper.succeed();
   }
}
