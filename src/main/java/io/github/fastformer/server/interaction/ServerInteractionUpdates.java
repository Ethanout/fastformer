package io.github.fastformer.server.interaction;

import io.github.fastformer.common.placement.InteractionUpdateScope;
import io.github.fastformer.server.settings.FastPlaceSettings;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Reads authoritative player policy at server block-interaction hooks. */
public final class ServerInteractionUpdates {
   private ServerInteractionUpdates() {
   }

   public static <T> T interact(Level level, Player player, Supplier<T> action) {
      return InteractionUpdateScope.interact(level, suppresses(level, player), action);
   }

   public static boolean suppresses(Level level, Player player) {
      if (player == null || !player.isCreative()) return false;
      if (level.isClientSide) return false;
      if (!(player instanceof ServerPlayer serverPlayer)) return false;
      FastPlaceSettings settings = FastPlaceSettings.load(serverPlayer);
      return settings.enabled() && settings.placementUpdateMode().suppressesNeighborUpdates();
   }
}
