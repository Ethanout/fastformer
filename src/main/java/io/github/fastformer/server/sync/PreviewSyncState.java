package io.github.fastformer.server.sync;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import io.github.fastformer.server.player.PlayerStateRegistry;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Transient publications for one player instance on its owning server thread. */
final class PreviewSyncState implements Scoped {
   private static final ScopedKey<PreviewSyncState> KEY = PlayerStateRegistry.key(
      ResourceLocation.parse("fastformer:preview_sync"), PreviewSyncState::new);

   static PreviewSyncState of(ServerPlayer player) { return PlayerStateRegistry.of(player).get(KEY); }

   static Optional<PreviewSyncState> find(ServerPlayer player) {
      return PlayerStateRegistry.find(player).flatMap(scope -> scope.find(KEY));
   }

   FastPlaceActivity lastActivity;
   long operationRevision;
   long buildingRevision;
   long geometryRevision;
   long activityRevision;
   QuickShapeSubmissionParametersPayload submissionParameters;
   private UUID callbackSession;

   UUID callbackSession() {
      if (callbackSession == null) beginClientSession();
      return callbackSession;
   }

   void beginClientSession() { callbackSession = UUID.randomUUID(); }

   void clear() {
      lastActivity = null;
      callbackSession = null;
      operationRevision = 0;
      buildingRevision = 0;
      geometryRevision = 0;
      activityRevision = 0;
      submissionParameters = null;
   }

   @Override public void onDetach() { clear(); }
   @Override public void onDimensionChanged() { clear(); }
   @Override public void onDiscard() { clear(); }
}
