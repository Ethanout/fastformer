package io.github.fastformer.network.sync;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.GeometryManager;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectResolver;
import io.github.fastformer.fastplace.session.FastPlaceSession;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectSnapshot;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends authoritative server snapshots to the current client connection. */
public final class PlayerPreviewSync {
   private static final Map<UUID, FastPlaceActivity> LAST_ACTIVITY = new ConcurrentHashMap<>();
   private static final Map<UUID, UUID> CLIENT_CALLBACK_SESSIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> OPERATION_PREVIEW_REVISIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> BUILDING_PREVIEW_REVISIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> GEOMETRY_PREVIEW_REVISIONS = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> ACTIVITY_REVISIONS = new ConcurrentHashMap<>();

   private PlayerPreviewSync() {
   }

   public static long buildingRevision(ServerPlayer player) {
      return BUILDING_PREVIEW_REVISIONS.getOrDefault(player.getUUID(), 0L);
   }

   public static void syncPreview(ServerPlayer player, FastPlaceSession session) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      var modes = FastPlaceManager.effectiveModes(settings, session);
      var activeEffect = PlaceableItems.placementState(
         player.getMainHandItem(), player, session.placementContext()
      ).flatMap(state -> PlacementEffectResolver.resolve(player, settings, session, state, modes))
         .map(io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect::id)
         .orElse(null);
      BuildingPreviewPayload payload = BuildingPreviewPayload.active(
            session.points(),
            session.faceBaseOffset(),
            session.volumeBaseOffset(),
            session.perpendicularAnchor(),
            modes.raycastPlacement(),
            session.modifierHeld(),
            session.polygonClosed(),
            session.polygonHeightConfirmed(),
            session.polygonVolumeShape(),
            session.freeScrollOffset(),
            session.faceTieBias(),
            session.placementContext(),
            activeEffect,
            settings
      );
      sendPreview(player, payload);
      syncActivity(player);
   }

   /** Starts a distinct callback scope for a newly connected client. */
   public static void beginClientSession(ServerPlayer player) {
      if (player != null) {
         CLIENT_CALLBACK_SESSIONS.put(player.getUUID(), UUID.randomUUID());
      }
   }

   public static OperationCallbackScope callbackScope(ServerPlayer player) {
      if (player == null) {
         throw new IllegalArgumentException("Operation callback player is required");
      }
      UUID sessionId = CLIENT_CALLBACK_SESSIONS.computeIfAbsent(player.getUUID(), ignored -> UUID.randomUUID());
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

   public static void syncSettings(ServerPlayer player) {
      sendPreview(player, BuildingPreviewPayload.inactive(FastPlaceSettings.load(player)));
      sendOperationPreview(player, OperationPreviewPayload.inactive(nextOperationPreviewRevision(player)));
      sendGeometry(player, GeometryPreviewPayload.inactive());
      syncActivity(player);
   }

   public static void syncGeometry(ServerPlayer player, GeometrySession session) {
      sendGeometry(
         player,
         new GeometryPreviewPayload(
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
            session.selectedControlPoint()
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
      if (LAST_ACTIVITY.put(player.getUUID(), activity) == activity) {
         return;
      }
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
      LAST_ACTIVITY.remove(player.getUUID());
      CLIENT_CALLBACK_SESSIONS.remove(player.getUUID());
      BUILDING_PREVIEW_REVISIONS.remove(player.getUUID());
      OPERATION_PREVIEW_REVISIONS.remove(player.getUUID());
      GEOMETRY_PREVIEW_REVISIONS.remove(player.getUUID());
      ACTIVITY_REVISIONS.remove(player.getUUID());
   }

   public static void clearServer() {
      LAST_ACTIVITY.clear();
      CLIENT_CALLBACK_SESSIONS.clear();
      OPERATION_PREVIEW_REVISIONS.clear();
      BUILDING_PREVIEW_REVISIONS.clear();
      GEOMETRY_PREVIEW_REVISIONS.clear();
      ACTIVITY_REVISIONS.clear();
   }

   private static long nextOperationPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return OPERATION_PREVIEW_REVISIONS.merge(player.getUUID(), 1L, Long::sum);
   }

   private static long nextGeometryPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return GEOMETRY_PREVIEW_REVISIONS.merge(player.getUUID(), 1L, Long::sum);
   }

   private static long nextActivityRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return ACTIVITY_REVISIONS.merge(player.getUUID(), 1L, Long::sum);
   }

   private static long nextBuildingPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return BUILDING_PREVIEW_REVISIONS.merge(player.getUUID(), 1L, Long::sum);
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

   private static void sendPreview(ServerPlayer player, BuildingPreviewPayload payload) {
      long revision = nextBuildingPreviewRevision(player);
      if (player.connection.hasChannel(BuildingPreviewSessionPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new BuildingPreviewSessionPayload(revision, payload.session(), callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
      if (player.connection.hasChannel(BuildingPreviewParametersPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            new BuildingPreviewParametersPayload(revision, payload.parameters(), callbackScope(player)),
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
      if (player.connection.hasChannel(GeometryPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player,
            payload.withRevision(nextGeometryPreviewRevision(player)).withCallbackScope(callbackScope(player)),
            new CustomPacketPayload[0]
         );
      }
   }
}
