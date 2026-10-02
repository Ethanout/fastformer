package io.github.fastformer.server.input;

import java.util.Map;
import java.util.WeakHashMap;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.raycast.ReachTransition;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

/** Tracks the distance transition against the current vanilla block reach. */
public final class ServerReachGate {
   private static final Map<ServerPlayer, Entry> STATES = new WeakHashMap<>();
   private record Entry(Level level, ReachTransition transition) { }

   private ServerReachGate() { }

   private static ReachTransition state(ServerPlayer player) {
      Entry entry = STATES.get(player);
      if (entry == null || entry.level() != player.level()) {
         entry = new Entry(player.level(), new ReachTransition());
         STATES.put(player, entry);
      }
      return entry.transition();
   }

   public static void tick(ServerPlayer player) {
      if (io.github.fastformer.fastplace.placement.replace.QuickReplaceManager.active(player)) {
         STATES.remove(player);
         return;
      }
      var settings = FastPlaceSettings.load(player);
      if (!player.isCreative() || !settings.enabled()) {
         STATES.remove(player);
         return;
      }
      var hit = LongRangeBlockRaycast.clipForReachTransition(player.level(), player, player.getEyePosition(), player.getViewVector(1)).hit();
      double distance = hit.getType() == HitResult.Type.BLOCK
         ? player.getEyePosition().distanceTo(hit.getLocation()) : Double.POSITIVE_INFINITY;
      state(player).update(distance, settings.reachThresholds(), player.blockInteractionRange());
   }

   public static boolean vanillaAt(ServerPlayer player, double distance) {
      if (io.github.fastformer.fastplace.placement.replace.QuickReplaceManager.active(player)) return true;
      return FastPlaceSettings.load(player).reachThresholds().vanillaAt(state(player).vanilla(), distance, player.blockInteractionRange());
   }

   public static void remove(ServerPlayer player) { STATES.remove(player); }
   public static void clear() { STATES.clear(); }
}
