package io.github.fastformer.server.sync;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.common.mode.SessionActivity;
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

   SessionActivity lastActivity;
   long operationRevision;
   long buildingRevision;
   long geometryRevision;
   long activityRevision;
   QuickShapeSubmissionParametersPayload submissionParameters;
   String buildingFacePolicyHash;
   private Object facePolicyRegistry;
   private io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet facePolicies;
   private UUID callbackSession;

   io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicySet facePolicies(net.minecraft.core.RegistryAccess registries) {
      Object registry = registries.registry(io.github.fastformer.common.geometry.raster.face.policy.FaceRasterRules.REGISTRY).orElse(null);
      if (facePolicies == null || registry != facePolicyRegistry) {
         facePolicyRegistry = registry;
         facePolicies = io.github.fastformer.common.geometry.raster.face.policy.FaceRasterRules.capture(registries);
      }
      return facePolicies;
   }

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
      buildingFacePolicyHash = null;
      facePolicyRegistry = null;
      facePolicies = null;
   }

   @Override public void onDetach() { clear(); }
   @Override public void onDimensionChanged() { clear(); }
   @Override public void onDiscard() { clear(); }
}
