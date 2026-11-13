package io.github.fastformer.testkit;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

/** Connected players for tests that must exercise login identity and real server callbacks. */
public final class ServerPlayers {
   private ServerPlayers() {}

   public static ServerPlayer connect(GameTestHelper helper, String name) {
      var profile = new GameProfile(UUID.randomUUID(), name);
      var cookie = CommonListenerCookie.createInitial(profile, false);
      var player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
      var connection = new Connection(PacketFlow.SERVERBOUND);
      new EmbeddedChannel(connection);
      player.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
      return player;
   }
}
