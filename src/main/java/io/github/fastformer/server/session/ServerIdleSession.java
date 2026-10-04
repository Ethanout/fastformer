package io.github.fastformer.server.session;

import io.github.fastformer.fastplace.session.IdleSessionState;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.network.payload.settings.IdleSessionPayload;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Server mirror of the client's idle session. It does not own a second distance latch. */
public final class ServerIdleSession {
   private record Entry(Level level, IdleSessionState state) { }
   private static final Map<ServerPlayer, Entry> STATES = new WeakHashMap<>();
   private ServerIdleSession() { }

   public static void receive(ServerPlayer player, IdleSessionPayload payload) {
      if (!payload.dimension().equals(player.level().dimension().location())) return;
      if (payload.state() != IdleSessionState.READY && eligible(player, payload.state())) STATES.put(player, new Entry(player.level(), payload.state()));
      else STATES.remove(player);
   }

   public static IdleSessionState state(ServerPlayer player) {
      Entry entry = STATES.get(player);
      if (entry == null) return IdleSessionState.READY;
      if (entry.level() != player.level() || !eligible(player, entry.state())) {
         STATES.remove(player);
         return IdleSessionState.READY;
      }
      return entry.state();
   }

   private static boolean eligible(ServerPlayer player, IdleSessionState state) {
      return player.isCreative() && FastPlaceSettings.load(player).enabled()
         && (state == IdleSessionState.MANUAL_FF || !FastPlaceManager.active(player) && !OperationManager.active(player) && !GeometryManager.active(player)
         && !FastPlaceManager.taskActive(player) && !OperationManager.taskActive(player)
         && !io.github.fastformer.fastplace.placement.replace.QuickReplaceManager.active(player));
   }

   public static void remove(ServerPlayer player) { STATES.remove(player); }
   public static void clear() { STATES.clear(); }
}
