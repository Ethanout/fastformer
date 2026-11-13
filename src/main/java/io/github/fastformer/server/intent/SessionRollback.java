package io.github.fastformer.server.intent;

import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.server.session.OperationManager;
import net.minecraft.server.level.ServerPlayer;

/** Rolls back one edit-session step without consuming committed world history. */
final class SessionRollback {
   private SessionRollback() {}

   static boolean active(ServerPlayer player) {
      return GeometryManager.active(player) || OperationManager.active(player) || FastPlaceManager.active(player);
   }

   static boolean undo(ServerPlayer player) {
      if (OperationManager.active(player)) {
         OperationManager.undo(player);
         return true;
      }
      if (GeometryManager.active(player)) {
         GeometrySession session = GeometryManager.session(player).orElse(null);
         if (session == null || !session.canUndoStep()) return false;
         boolean stillActive = session.undoStep();
         if (stillActive && session.canUndoStep()) GeometryManager.sync(player);
         else GeometryManager.cancel(player);
         return true;
      }
      if (FastPlaceManager.active(player)) {
         var draft = FastPlaceManager.session(player).orElse(null);
         if (draft == null || !draft.canUndoStep()) return false;
         FastPlaceManager.undo(player);
         return true;
      }
      return false;
   }
}
