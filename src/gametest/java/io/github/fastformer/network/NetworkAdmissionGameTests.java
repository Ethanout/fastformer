package io.github.fastformer.network;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.network.payload.operation.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.payload.placement.ShapePlacementPayload;
import io.github.fastformer.network.payload.settings.SettingsActionPayload;
import io.github.fastformer.server.session.OperationManager;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NetworkAdmissionGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "network_falling_permissions", timeoutTicks = 600)
   public static void fallingRuleRequiresOperatorAndUsesExplicitTarget(GameTestHelper helper) throws Exception {
      ServerPlayer player = createPlayer(helper);
      var server = player.getServer();
      var rules = io.github.fastformer.fastplace.world.BlockActivityRules.get(server);
      boolean original = rules.fallingDisabled();
      boolean originallyFrozen = server.tickRateManager().isFrozen();
      boolean operator = server.getPlayerList().isOp(player.getGameProfile());
      try {
         server.getPlayerList().deop(player.getGameProfile());
         helper.assertFalse(player.hasPermissions(2), "test player still has operator permission");
         rules.setFallingDisabled(false);
         for (GameType mode : new GameType[] {GameType.SURVIVAL, GameType.CREATIVE}) {
            player.setGameMode(mode);
            receive("handleSettingsAction", new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_FALLING_DISABLED, true), player);
            helper.assertFalse(rules.fallingDisabled(), "unprivileged player changed the falling rule in " + mode);
         }
         server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 2, false));
         helper.assertTrue(player.hasPermissions(2), "test operator lacks permission");
         receive("handleSettingsAction", new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_FALLING_DISABLED, true), player);
         helper.assertTrue(rules.fallingDisabled(), "operator could not disable falling");
         receive("handleSettingsAction", new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_FALLING_DISABLED, true), player);
         helper.assertTrue(rules.fallingDisabled(), "replayed explicit target toggled the state");
         receive("handleSettingsAction", new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_FALLING_DISABLED, false), player);
         helper.assertFalse(rules.fallingDisabled(), "operator could not enable falling");
         helper.assertTrue(server.tickRateManager().isFrozen() == originallyFrozen, "falling rule changed tick freeze");
      } finally {
         rules.setFallingDisabled(original);
         if (!operator) server.getPlayerList().deop(player.getGameProfile());
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "network_upload_permissions", timeoutTicks = 600)
   public static void bothUploadKindsRejectSurvivalBeforeRetainingChunks(GameTestHelper helper) throws Exception {
      ServerPlayer player = createPlayer(helper);
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      BlockPos origin = helper.absolutePos(new BlockPos(1, 5, 1));
      OperationManager.startFirst(player, origin);
      OperationManager.startSecond(player, origin.offset(2, 0, 0));
      var draft = OperationManager.session(player).orElseThrow();
      player.setGameMode(GameType.SURVIVAL);
      UUID workspace = UUID.randomUUID(), shape = UUID.randomUUID();
      receive("handleOperationWorkspaceApply", new OperationWorkspaceApplyPayload(workspace, 0, 2, new byte[] {1}), player);
      receive("handleShapePlacement", new ShapePlacementPayload(shape, 0, 2, new byte[] {1}), player);
      helper.assertFalse(FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), workspace), "workspace upload retained its scope");
      helper.assertFalse(FastPlaceNetwork.hasCallbackScopeForTest(player.getUUID(), shape), "shape upload retained its scope");
      helper.assertFalse(OperationManager.taskActive(player), "denied upload started a task");
      helper.assertTrue(OperationManager.session(player).orElse(null) == draft, "denied upload cleared the draft");
      OperationManager.cancel(player);
      helper.succeed();
   }

   private static ServerPlayer createPlayer(GameTestHelper helper) {
      var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "permission-test");
      var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
      var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
      var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
      new io.netty.channel.embedded.EmbeddedChannel(connection);
      helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
      return player;
   }

   private static void receive(String handler, Object payload, ServerPlayer player) throws Exception {
      IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
         new Class<?>[] {IPayloadContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("player")) return player;
            if (method.getName().equals("enqueueWork")) {
               if (args[0] instanceof Runnable runnable) { runnable.run(); return CompletableFuture.completedFuture(null); }
               return CompletableFuture.completedFuture(((java.util.function.Supplier<?>) args[0]).get());
            }
            throw new UnsupportedOperationException(method.getName());
         });
      var method = FastPlaceNetwork.class.getDeclaredMethod(handler, payload.getClass(), IPayloadContext.class);
      method.setAccessible(true);
      method.invoke(null, payload, context);
   }
}
