package io.github.fastformer.server.command;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import net.minecraft.server.level.ServerPlayer;

/** Feature-owned command declarations and execution adapters. */
public final class CommandChecks {
   private CommandChecks() {}

   public static boolean validNumber(ServerPlayer player, double... values) {
      if (GeometryNumbers.finite(values)) {
         return true;
      }
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.number_invalid"));
      return false;
   }

}
