package io.github.fastformer.server.movement;

import io.github.fastformer.fastplace.geometry.movement.ForwardDashTraversal;
import io.github.fastformer.fastplace.settings.FlightSettings;
import io.github.fastformer.protocol.s2c.movement.ForwardDashStartPayload;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Authorizes a collision-safe flight dash; ordinary movement packets synchronize its progress. */
public final class ForwardDash {
   private static final ScopedKey<Requests> REQUESTS = PlayerStateRegistry.key(ResourceLocation.parse("fastformer:forward_dash"), Requests::new);

   private ForwardDash() { }

   public static void execute(ServerPlayer player, long requestId, int distance) {
      if (distance < 0 || distance > FlightSettings.MAX_DASH_DISTANCE) return;
      if (!player.isAlive() || player.isSpectator() || player.isPassenger()
         || !player.getAbilities().mayfly || !player.getAbilities().flying
         || !NetworkRegistry.hasChannel(player.connection, ForwardDashStartPayload.TYPE.id())) return;
      var level = player.serverLevel();
      Requests requests = PlayerStateRegistry.of(player).get(REQUESTS);
      Long previous = requests.last;
      if (previous != null && requestId <= previous) return;
      requests.last = requestId;
      Vec3 movement = player.getLookAngle().scale(distance);
      double fraction = ForwardDashTraversal.COLLISION_SAFE.clearFraction(player, movement);
      Vec3 destination = player.position().add(movement.scale(fraction));
      PacketDistributor.sendToPlayer(player, new ForwardDashStartPayload(requestId, level.dimension().location(),
         player.getId(), player.position(), destination));
   }
   private static final class Requests implements Scoped {
      private Long last;
      @Override public void onDetach() { last = null; }
      @Override public void onDimensionChanged() { last = null; }
      @Override public void onDiscard() { last = null; }
   }
}
