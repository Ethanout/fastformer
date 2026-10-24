package io.github.fastformer.server.movement;

import io.github.fastformer.fastplace.geometry.movement.ForwardDashTraversal;
import io.github.fastformer.fastplace.settings.FlightSettings;
import io.github.fastformer.network.payload.movement.ForwardDashStartPayload;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Authorizes a collision-safe flight dash; ordinary movement packets synchronize its progress. */
public final class ForwardDash {
   private static final Map<ServerPlayer, Long> LAST_REQUEST = new WeakHashMap<>();

   private ForwardDash() { }

   public static void execute(ServerPlayer player, long requestId, int distance) {
      if (distance < 0 || distance > FlightSettings.MAX_DASH_DISTANCE) return;
      if (!player.isAlive() || player.isSpectator() || player.isPassenger()
         || !player.getAbilities().mayfly || !player.getAbilities().flying
         || !NetworkRegistry.hasChannel(player.connection, ForwardDashStartPayload.TYPE.id())) return;
      var level = player.serverLevel();
      Long previous = LAST_REQUEST.get(player);
      if (previous != null && requestId <= previous) return;
      LAST_REQUEST.put(player, requestId);
      Vec3 movement = player.getLookAngle().scale(distance);
      double fraction = ForwardDashTraversal.COLLISION_SAFE.clearFraction(player, movement);
      Vec3 destination = player.position().add(movement.scale(fraction));
      PacketDistributor.sendToPlayer(player, new ForwardDashStartPayload(requestId, level.dimension().location(),
         player.getId(), player.position(), destination));
   }
}
