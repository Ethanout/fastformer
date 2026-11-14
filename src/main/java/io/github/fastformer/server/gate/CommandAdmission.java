package io.github.fastformer.server.gate;

import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.server.session.SessionSystem;
import net.minecraft.server.level.ServerPlayer;

/** Command admission retains editing cancellation and the existing rejection feedback. */
public final class CommandAdmission {
   private CommandAdmission() {}

   public static boolean available(ServerPlayer player) {
      if (ServerOperationGates.AVAILABLE.test(player).allowed()) return true;
      SessionSystem.of(player).stopUnavailable(player);
      PlayerMessages.chat(player, PlayerMessages.text(
         RecoverySystem.of(player.getServer()).writeGate().writesAllowed()
            ? "fastformer.message.command_unavailable" : "fastformer.message.recovery_journal_blocked"));
      return false;
   }
}
