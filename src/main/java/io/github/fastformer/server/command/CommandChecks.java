package io.github.fastformer.server.command;

import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.server.feedback.PlayerMessages;
import net.minecraft.server.level.ServerPlayer;

/** Feature-owned command declarations and execution adapters. */
public final class CommandChecks {
   private CommandChecks() {}

   public static boolean validNumber(ServerPlayer player, double... values) {
      if (GeometryNumbers.finite(values)) {
         return true;
      }
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.number_invalid"));
      return false;
   }

}
