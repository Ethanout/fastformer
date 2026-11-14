package io.github.fastformer.server.intent;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.testkit.ServerPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeometryIntentGameTests {
   private GeometryIntentGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "geometry_intents", timeoutTicks = 600)
   public static void modeSelectionSharesAdmissionAcrossSources(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      try {
         var select = new GeometryIntents.SelectMode(GeometryMode.POLYHEDRON);
         helper.assertTrue(dispatch(player, select), "network mode selection refused");
         GeometrySystem geometry = GeometrySystem.of(player);
         helper.assertTrue(geometry.session(player).orElseThrow().mode() == GeometryMode.POLYHEDRON, "wrong geometry mode");
         GeometrySession session = geometry.session(player).orElseThrow();
         session.addPoint(BlockPos.ZERO);
         helper.assertFalse(dispatch(player, new GeometryIntents.SelectMode(GeometryMode.CONE_PRISM)),
            "mode selection replaced a started geometry");
         helper.assertTrue(geometry.session(player).orElseThrow() == session && session.mode() == GeometryMode.POLYHEDRON,
            "refused selection changed the session");

         SessionSystem.of(player).cancelEditing(player);
         OperationSystem.of(player).startFirst(player, BlockPos.ZERO);
         helper.assertFalse(dispatch(player, select), "geometry replaced an active selection");
         SessionSystem.of(player).cancelEditing(player);
         player.setGameMode(GameType.SURVIVAL);
         helper.assertFalse(dispatch(player, select), "survival player entered geometry through an intent");
      } finally { cleanup(player); }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_intents", timeoutTicks = 600)
   public static void coneAndPolyhedronEditsRetainWorkflowValidation(GameTestHelper helper) {
      ServerPlayer player = player(helper);
      try {
         dispatch(player, new GeometryIntents.SelectMode(GeometryMode.CONE_PRISM));
         helper.assertTrue(dispatch(player, new GeometryIntents.ConePlane(ConePlaneMode.DIAMETER)), "cone plane refused");
         helper.assertTrue(dispatch(player, new GeometryIntents.ConeEllipse(2, 3)), "ellipse refused");
         GeometrySession cone = GeometrySystem.of(player).session(player).orElseThrow();
         helper.assertTrue(cone.cone().coneScaleX() == 2 && cone.cone().coneScaleZ() == 3, "ellipse values lost");
         helper.assertTrue(FastPlaceSettings.load(player).conePlaneMode() == ConePlaneMode.DIAMETER,
            "cone plane preference was not saved");
         helper.assertFalse(dispatch(player, new GeometryIntents.Euler(10, 20, 30)), "polyhedron rotation accepted by cone");
         helper.assertFalse(dispatch(player, new GeometryIntents.ConeEllipse(Double.NaN, 1)), "non-finite ellipse accepted");

         SessionSystem.of(player).cancelEditing(player);
         dispatch(player, new GeometryIntents.SelectMode(GeometryMode.POLYHEDRON));
         helper.assertTrue(dispatch(player, new GeometryIntents.PolyhedronSize(PolyhedronSizeMode.DIAMETER)), "polyhedron size refused");
         GeometrySession polyhedron = GeometrySystem.of(player).session(player).orElseThrow();
         helper.assertTrue(polyhedron.polyhedron().polyhedronSizeMode() == PolyhedronSizeMode.DIAMETER, "polyhedron size lost");
         double[] before = polyhedron.polyhedron().rotation();
         helper.assertFalse(dispatch(player, new GeometryIntents.Matrix(new double[8])), "invalid matrix accepted");
         helper.assertFalse(dispatch(player, new GeometryIntents.RotationStep(3, 1, false)), "invalid axis accepted");
         sameRotation(helper, before, polyhedron.polyhedron().rotation());
      } finally { cleanup(player); }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "geometry_intents", timeoutTicks = 600)
   public static void commandAndNetworkRotationUseTheSameIntentHandlers(GameTestHelper helper) throws Exception {
      ServerPlayer player = player(helper);
      try {
         var commands = player.getServer().getCommands().getDispatcher();
         var source = player.createCommandSourceStack();
         helper.assertTrue(commands.execute("ff geometry mode polyhedron", source) == 1, "command mode refused");
         var session = GeometrySystem.of(player).session(player).orElseThrow();
         helper.assertTrue(commands.execute("ff rotate euler 15 30 45", source) == 1, "Euler command refused");
         double[] expected = session.polyhedron().rotation();
         dispatch(player, new GeometryIntents.Euler(0, 0, 0));
         helper.assertTrue(dispatch(player, new GeometryIntents.Euler(15, 30, 45)), "Euler intent refused");
         sameRotation(helper, expected, session.polyhedron().rotation());

         helper.assertTrue(commands.execute("ff rotate quat 1 0 0 0", source) == 1, "quaternion command refused");
         helper.assertTrue(commands.execute("fastformer geometry rotate snap y 2", source) == 1, "aliased snap command refused");
         expected = session.polyhedron().rotation();
         dispatch(player, new GeometryIntents.Quaternion(1, 0, 0, 0));
         dispatch(player, new GeometryIntents.RotationStep(1, 2, false));
         sameRotation(helper, expected, session.polyhedron().rotation());

         double[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
         var matrix = new GeometryIntents.Matrix(identity);
         identity[0] = 99;
         matrix.values()[4] = 99;
         helper.assertTrue(dispatch(player, matrix), "matrix snapshot was changed through its input or output array");
         sameRotation(helper, new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, session.polyhedron().rotation());
      } finally { cleanup(player); }
      helper.succeed();
   }

   private static boolean dispatch(ServerPlayer player, Intent intent) {
      return PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, intent).handled();
   }

   private static void sameRotation(GameTestHelper helper, double[] expected, double[] actual) {
      helper.assertTrue(expected.length == actual.length, "rotation size changed");
      for (int index = 0; index < expected.length; index++) {
         helper.assertTrue(Math.abs(expected[index] - actual[index]) < 1.0E-9, "rotation differs at " + index);
      }
   }

   private static ServerPlayer player(GameTestHelper helper) {
      ServerPlayer player = ServerPlayers.connect(helper, "geometry-intent-test");
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      return player;
   }

   private static void cleanup(ServerPlayer player) {
      SessionSystem.of(player).discardPlayer(player);
      PlayerStateRegistry.detach(player);
      player.getServer().getPlayerList().remove(player);
   }
}
