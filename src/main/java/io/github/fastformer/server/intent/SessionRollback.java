package io.github.fastformer.server.intent;

import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.server.level.ServerPlayer;

/** Rolls back one edit-session step without consuming committed world history. */
final class SessionRollback {
   private SessionRollback() {}

   static boolean active(ServerPlayer player) {
      return GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player) || QuickShapeSystem.of(player).active(player);
   }

   static boolean undo(ServerPlayer player) {
      if (OperationSystem.of(player).active(player)) {
         OperationSystem.of(player).undo(player);
         return true;
      }
      if (GeometrySystem.of(player).active(player)) {
         GeometrySession session = GeometrySystem.of(player).session(player).orElse(null);
         if (session == null || !session.canUndoStep()) return false;
         boolean stillActive = session.undoStep();
         if (stillActive && session.canUndoStep()) GeometrySystem.of(player).sync(player);
         else GeometrySystem.of(player).cancel(player);
         return true;
      }
      if (QuickShapeSystem.of(player).active(player)) {
         var draft = QuickShapeSystem.of(player).session(player).orElse(null);
         if (draft == null || !draft.canUndoStep()) return false;
         QuickShapeSystem.of(player).undo(player);
         return true;
      }
      return false;
   }
}
