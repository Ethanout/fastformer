package io.github.fastformer.client.input;

import io.github.fastformer.network.payload.world.HistoryConflictPayload;
import io.github.fastformer.network.payload.world.HistoryConflictResponsePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

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
   public static boolean handleKey(Minecraft minecraft, KeyboardInputSnapshot key) {
      if (pending == null || minecraft.player == null || minecraft.screen != null || key.action() != 1) return false;
      var choice = choice(key.key(), key.controlDown());
      if (choice == null) return key.controlDown() && (key.key() == 90 || key.key() == 89);
      if (!sent && minecraft.getConnection() != null) {
         PacketDistributor.sendToServer(new HistoryConflictResponsePayload(pending.token(), choice));
         sent = true;
      }
      return true;
   }

   static HistoryConflictResponsePayload.Choice choice(int key, boolean control) {
      return key == 81 ? HistoryConflictResponsePayload.Choice.CANCEL
         : key == 257 || key == 335 ? control
            ? HistoryConflictResponsePayload.Choice.SKIP : HistoryConflictResponsePayload.Choice.OVERWRITE : null;
   }
}
