package io.github.fastformer.server.sync;

import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.server.session.OperationManager;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectResolver;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewEffectSnapshot;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends authoritative server snapshots to the current client connection. */
public final class PreviewSync {
   private PreviewSync() {
   }

   public static long buildingRevision(ServerPlayer player) {
      return PreviewSyncState.find(player).map(state -> state.buildingRevision).orElse(0L);
   }

   public static long geometryRevision(ServerPlayer player) {
      return PreviewSyncState.find(player).map(state -> state.geometryRevision).orElse(0L);
   }

   public static long operationRevision(ServerPlayer player) {
      return PreviewSyncState.find(player).map(state -> state.operationRevision).orElse(0L);
   }

   public static boolean buildingSubmissionParametersMatch(ServerPlayer player) {
      var published = PreviewSyncState.find(player).map(state -> state.submissionParameters).orElse(null);
      var session = FastPlaceManager.session(player).orElse(null);
      if (published == null || session == null || published.revision() != buildingRevision(player)) return false;
      var settings = FastPlaceSettings.load(player);
      return published.maxPlacement() == settings.maxPlacement()
         && published.faceTieBias() == FastPlaceManager.effectiveFaceTieBias(session, settings)
         && PlaceableItems.placementState(player.getMainHandItem(), player, session.placementContext())
            .filter(published.prototype()::equals).isPresent();
   }

   public static void syncPreview(ServerPlayer player, QuickShapeDraft session) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      var modes = FastPlaceManager.effectiveModes(settings, session);
      var activeEffect = PlaceableItems.placementState(
         player.getMainHandItem(), player, session.placementContext()
      ).flatMap(state -> PlacementEffectResolver.resolve(player, settings, session, state, modes))
         .map(io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect::id)
         .orElse(null);
      BuildingPreviewSnapshot payload = QuickShapePreviews.active(session, settings, modes, activeEffect);
      sendPreview(player, payload);
      syncActivity(player);
   }

   /** Starts a distinct callback scope for a newly connected client. */
   public static void beginClientSession(ServerPlayer player) {
      if (player != null) {
         PreviewSyncState.of(player).beginClientSession();
      }
   }

   public static OperationCallbackScope callbackScope(ServerPlayer player) {
      if (player == null) {
         throw new IllegalArgumentException("Operation callback player is required");
      }
      UUID sessionId = PreviewSyncState.of(player).callbackSession();
      return new OperationCallbackScope(player.getUUID(), player.level().dimension().location(), sessionId);
   }

   public static void syncOperation(ServerPlayer player, OperationSession session) {
      sendOperationPreview(
         player,
         OperationPreviewPayload.active(
            nextOperationPreviewRevision(player),
            session.hasFirst(),
            session.hasSecond(),
            session.points(),
            session.cuboidMinPoint(),
            session.cuboidMaxPoint(),
            session.minOffset(),
            session.maxOffset(),
            session.selectionMode(),
            session.prismBasePointCount(),
            session.selectedPointIndex(),
            session.hullInflation(),
            session.mode(),
            session.stageMode(),
            session.translation(),
            session.stackRegion().min(),
            session.stackRegion().max(),
            session.rotation(),
            session.adjustmentStarted(),
            FastPlaceManager.modifierHeld(player),
            FastPlaceManager.modifierHeld(player)
         )
      );
      syncActivity(player);
   }

   public static void clearPreview(ServerPlayer player) {
      syncSettings(player);
   }

   public static void syncInteractionUpdates(ServerPlayer player) {
      var settings = FastPlaceSettings.load(player);
      var payload = new io.github.fastformer.protocol.s2c.settings.InteractionUpdatesPayload(
         settings.enabled() && settings.placementUpdateMode().suppressesNeighborUpdates(),
         settings.enabled() && settings.emptyHandWrench(), settings.enabled() && settings.forcePlacement(),
         settings.skipRepeatedCropStages());
      if (player.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(player, payload);
   }

   public static void syncSettings(ServerPlayer player) {
      syncInteractionUpdates(player);
      sendPreview(player, QuickShapePreviews.inactive(FastPlaceSettings.load(player)));
      sendOperationPreview(player, OperationPreviewPayload.inactive(nextOperationPreviewRevision(player),
         FastPlaceSettings.load(player).operationSelectionMode()));
      sendGeometry(player, GeometryPreviewPayload.inactive());
      syncActivity(player);
   }

   public static void syncGeometry(ServerPlayer player, GeometrySession session) {
      sendGeometry(
         player,
         new GeometryPreviewPayload(
            0L,
            session.draftId(),
            true,
            session.mode(),
            session.points(),
            session.pointLocations(),
            session.pointRoles(),
            session.closed(),
            FastPlaceManager.modifierHeld(player),
            session.extrusion(),
            session.polyhedronShapeVariant(),
            session.coneShapeVariant(),
            session.compoundShapeVariant(),
            session.polyhedronSizeMode(),
            FastPlaceSettings.load(player).fillMode(),
            session.conePlaneMode(),
            session.coneRadius(),
            session.coneScaleX(),
            session.coneScaleZ(),
            session.coneTopScaleOffset(),
            session.coneTopOffset(),
            session.coneRotationRadians(),
            session.coneGizmoLocal(),
            session.rotation(),
            session.polyhedronLocalScale(),
            session.polyhedronWorldScale(),
            session.polyhedronGizmoLocal(),
            session.selectedControlPoint(),
            callbackScope(player)
         )
      );
      syncActivity(player);
   }

   public static void clearGeometry(ServerPlayer player) {
      sendGeometry(player, GeometryPreviewPayload.inactive());
      syncActivity(player);
   }

   public static void syncActivity(ServerPlayer player) {
      FastPlaceActivity activity = currentActivity(player);
      var state = PreviewSyncState.of(player);
      if (state.lastActivity == activity) {
         return;
      }
      state.lastActivity = activity;
      if (player.connection.hasChannel(ActivityStatePayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new ActivityStatePayload(nextActivityRevision(player), activity, callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
   }

   public static void forgetActivity(ServerPlayer player) {
      if (player == null) {
         return;
      }
      PreviewSyncState.find(player).ifPresent(PreviewSyncState::clear);
   }

   private static long nextOperationPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return ++PreviewSyncState.of(player).operationRevision;
   }

   private static long nextGeometryPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return ++PreviewSyncState.of(player).geometryRevision;
   }

   private static long nextActivityRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return ++PreviewSyncState.of(player).activityRevision;
   }

   private static long nextBuildingPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return ++PreviewSyncState.of(player).buildingRevision;
   }

   private static FastPlaceActivity currentActivity(ServerPlayer player) {
      if (FastPlaceManager.restoreActive(player) || OperationManager.restoreActive(player)) {
         return FastPlaceActivity.RESTORE_TASK;
      }
      if (OperationManager.taskActive(player)) {
         return FastPlaceActivity.OPERATION_TASK;
      }
      if (FastPlaceManager.taskActive(player)) {
         return FastPlaceActivity.PLACEMENT_TASK;
      }
      if (OperationManager.active(player)) {
         return FastPlaceActivity.OPERATION_SESSION;
      }
      if (GeometryManager.active(player)) {
         return FastPlaceActivity.GEOMETRY_SESSION;
      }
      if (FastPlaceManager.active(player)) {
         return FastPlaceActivity.BUILDING_SESSION;
      }
      return FastPlaceActivity.NONE;
   }

   private static void sendPreview(ServerPlayer player, BuildingPreviewSnapshot payload) {
      long revision = nextBuildingPreviewRevision(player);
      var draft = payload.active() ? FastPlaceManager.session(player).orElse(null) : null;
      var settings = FastPlaceSettings.load(player);
      var sessionValue = draft == null ? payload.session() : payload.session().withProfile(draft.profileId(),
         io.github.fastformer.server.interaction.profile.ProfileConstraints.middleConfirm(draft, settings));
      var parameterValue = payload.parameters().withLineMode(
         io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(draft, settings));
      if (player.connection.hasChannel(BuildingPreviewSessionPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new BuildingPreviewSessionPayload(revision, sessionValue, callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
      if (player.connection.hasChannel(BuildingPreviewParametersPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new BuildingPreviewParametersPayload(revision, parameterValue, callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
      if (player.connection.hasChannel(BuildingPreviewEffectPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new BuildingPreviewEffectPayload(
               revision, new BuildingPreviewEffectSnapshot(payload.activePlacementEffect()), callbackScope(player)
            ),
            new CustomPacketPayload[0]
         );
      }
      sendSubmissionParameters(player, payload, revision);
   }

   private static void sendSubmissionParameters(ServerPlayer player, BuildingPreviewSnapshot payload, long revision) {
      if (!payload.active()) {
         PreviewSyncState.of(player).submissionParameters = null;
         return;
      }
      var session = FastPlaceManager.session(player).orElse(null);
      if (session == null) return;
      var settings = FastPlaceSettings.load(player);
      var state = PlaceableItems.placementState(player.getMainHandItem(), player, session.placementContext())
         .orElse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
      var parameters = new QuickShapeSubmissionParametersPayload(
         revision, settings.maxPlacement(), state, FastPlaceManager.effectiveFaceTieBias(session, settings), callbackScope(player)
      );
      PreviewSyncState.of(player).submissionParameters = parameters;
      if (player.connection.hasChannel(QuickShapeSubmissionParametersPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, parameters);
      }
   }

   private static void sendOperationPreview(ServerPlayer player, OperationPreviewPayload payload) {
      if (player.connection.hasChannel(OperationPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            payload.withCallbackScope(callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
   }

   private static void sendGeometry(ServerPlayer player, GeometryPreviewPayload payload) {
      long revision = nextGeometryPreviewRevision(player);
      if (player.connection.hasChannel(GeometryPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            payload.withRevision(revision).withCallbackScope(callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
   }
}
