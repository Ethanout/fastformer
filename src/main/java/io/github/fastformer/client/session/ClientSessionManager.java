package io.github.fastformer.client.session;

import io.github.fastformer.client.FastPlaceClientPreview;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.empty.EmptySession;
import io.github.fastformer.client.session.quickshape.QuickShapeSession;
import io.github.fastformer.client.session.specialitem.SpecialItemSession;
import io.github.fastformer.client.session.specialshape.SpecialShapeSession;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionManager {
   private static final ClientSessionManager INSTANCE = new ClientSessionManager();

   private final List<ClientSession> sessions = List.of(
      new SpecialItemSession(),
      new SpecialShapeSession(),
      new QuickShapeSession(),
      new EmptySession()
   );
   private ClientSession current = sessions.getLast();

   private ClientSessionManager() {
   }

   public static ClientSessionManager instance() {
      return INSTANCE;
   }

   public ClientSessionState state() {
      return current.state();
   }

   public ClientSessionSnapshot snapshot(Minecraft minecraft) {
      return new ClientSessionSnapshot(
         FastPlaceClientPreview.active(),
         FastPlaceClientPreview.geometryActive() || FastPlaceClientPreview.operationActive(),
         QuickReplaceMode.active() && minecraft != null && minecraft.player != null
      );
   }

   public ClientSessionState refresh(Minecraft minecraft) {
      ClientSessionSnapshot snapshot = snapshot(minecraft);
      ClientSession next = sessions.stream()
         .filter(session -> session.matches(snapshot))
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("No client session matches the current snapshot"));
      if (next != current) {
         current.exit();
         current = next;
         current.enter();
      }
      return current.state();
   }
}
