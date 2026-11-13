package io.github.fastformer.server.command;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.server.input.ServerInputDispatcher;
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

   public static boolean commandCanOperate(ServerPlayer player) {
      if (ServerInputDispatcher.canOperate(player)) {
         return true;
      }
      ServerInputDispatcher.stopBecauseUnavailable(player);
      FastPlaceMessages.chat(
         player,
         FastPlaceMessages.text(
            RecoverySystem.of(player.getServer()).writeGate().writesAllowed()
               ? "fastformer.message.command_unavailable"
               : "fastformer.message.recovery_journal_blocked"
         )
      );
      return false;
   }
}
