package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;
import io.github.fastformer.client.interaction.binding.action.HistoryConflictCommand;

import io.github.fastformer.protocol.s2c.world.HistoryConflictPayload;
import io.github.fastformer.protocol.c2s.world.HistoryConflictResponsePayload;
import net.minecraft.client.Minecraft;

/** Owns the prompt until the server accepts a token-scoped answer. */
public final class HistoryConflictConfirmation {
   private static HistoryConflictPayload pending;
   private static boolean sent;
   private static final java.util.List<net.minecraft.core.BlockPos> positions = new java.util.ArrayList<>();
   private HistoryConflictConfirmation() { }
   public static HistoryConflictPayload pending() { return pending; }
   public static java.util.List<net.minecraft.core.BlockPos> positions() { return java.util.Collections.unmodifiableList(positions); }
   public static void clear() { pending = null; sent = false; positions.clear(); }
   public static void receive(HistoryConflictPayload payload) {
      if (payload.total() == 0) {
         if (pending != null && pending.token().equals(payload.token())) clear();
      } else {
         if (pending == null || !pending.token().equals(payload.token())) {
            clear(); pending = payload;
         }
         if (positions.size() + payload.positions().size() <= pending.total()) positions.addAll(payload.positions());
      }
   }
   static boolean respond(Minecraft minecraft, HistoryConflictCommand command) {
      if (pending == null || minecraft.player == null || minecraft.screen != null) return false;
      if (!sent && minecraft.getConnection() != null) {
         var choice = switch (command) {
            case OVERWRITE -> HistoryConflictResponsePayload.Choice.OVERWRITE;
            case SKIP -> HistoryConflictResponsePayload.Choice.SKIP;
            case CANCEL -> HistoryConflictResponsePayload.Choice.CANCEL;
         };
         ServerGateway.send(new HistoryConflictResponsePayload(pending.token(), choice));
         sent = true;
      }
      return true;
   }
}
