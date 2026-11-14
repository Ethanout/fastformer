package io.github.fastformer.server.api;

import io.github.fastformer.common.api.ModeChanged;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.server.session.SessionSystem;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/** Notifications for temporary modes whose state already belongs to a connection scope. */
public final class ModeNotifications {
   private ModeNotifications() {}

   public static void opened(ServerPlayer player, ModeId mode) { changed(player, mode, false, true); }
   public static void ended(ServerPlayer player, ModeId mode) { changed(player, mode, true, false); }

   private static void changed(ServerPlayer player, ModeId mode, boolean previous, boolean current) {
      SessionSystem.find(player.getServer()).ifPresent(runtime -> runtime.eventStream().publish(new ModeChanged(
         player.getUUID(), mode.value(), previous ? Optional.of(mode) : Optional.empty(),
         current ? Optional.of(mode) : Optional.empty(), current ? ModeChanged.Cause.OPEN : ModeChanged.Cause.END)));
   }
}
