package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.LineMode;
import io.github.fastformer.fastplace.PointMode;
import io.github.fastformer.fastplace.PolygonVolumeShape;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.RaycastPlacement;
import io.github.fastformer.fastplace.VolumeMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record BuildingPreviewPayload(
      boolean enabled,
      boolean middleConfirmEnabled,
      boolean active,
      boolean ctrlHeld,
      boolean polygonClosed,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points,
      int angleDistance,
      BlockPos freeScrollOffset,
      Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor,
      double angleDegrees,
      PointMode pointMode,
      RaycastPlacement raycastPlacement,
      LineMode lineMode,
      FaceMode faceMode,
      VolumeMode volumeMode,
      FillMode fillMode,
      LineTieBias faceTieBias,
      FaceRasterizationMode faceRasterizationMode,
      PlacementContextSnapshot placementContext,
      ResourceLocation activePlacementEffect
) implements CustomPacketPayload {
   private static final int MAX_PREVIEW_POINTS = 1024;
   public static final Type<BuildingPreviewPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "building_preview"));
   public static final StreamCodec<FriendlyByteBuf, BuildingPreviewPayload> STREAM_CODEC = CustomPacketPayload.codec(
      BuildingPreviewPayload::write,
      BuildingPreviewPayload::new
   );

   public BuildingPreviewPayload {
      polygonVolumeShape = polygonVolumeShape == null ? PolygonVolumeShape.EXTRUDE : polygonVolumeShape;
      faceTieBias = faceTieBias == null ? LineTieBias.DEFAULT : faceTieBias;
      faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : faceRasterizationMode;
      points = List.copyOf(points);
      faceBaseOffset = GeometryNumbers.finiteOrZero(faceBaseOffset);
      volumeBaseOffset = GeometryNumbers.finiteOrZero(volumeBaseOffset);
      angleDegrees = GeometryNumbers.finiteOr(angleDegrees, 0.0);
   }

   public BuildingPreviewPayload(
      boolean enabled,
      boolean middleConfirmEnabled,
      boolean active,
      boolean ctrlHeld,
      boolean polygonClosed,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points,
      int angleDistance,
      BlockPos freeScrollOffset,
      Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor,
      double angleDegrees,
      PointMode pointMode,
      RaycastPlacement raycastPlacement,
      LineMode lineMode,
      FaceMode faceMode,
      VolumeMode volumeMode,
      FillMode fillMode,
      LineTieBias faceTieBias,
      PlacementContextSnapshot placementContext
   ) {
      this(
         enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed, polygonHeightConfirmed,
         polygonVolumeShape, points, angleDistance, freeScrollOffset, faceBaseOffset, volumeBaseOffset,
         perpendicularAnchor, angleDegrees, pointMode, raycastPlacement, lineMode, faceMode, volumeMode,
         fillMode, faceTieBias, FaceRasterizationMode.POINT_SWEEP, placementContext, null
      );
   }

   private BuildingPreviewPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readEnum(PolygonVolumeShape.class),
         readPoints(buffer),
         buffer.readVarInt(),
         buffer.readBlockPos(),
         readVec3(buffer),
         readVec3(buffer),
         buffer.readBlockPos(),
         buffer.readDouble(),
         buffer.readEnum(PointMode.class),
         buffer.readEnum(RaycastPlacement.class),
         buffer.readEnum(LineMode.class),
         buffer.readEnum(FaceMode.class),
         buffer.readEnum(VolumeMode.class),
         buffer.readEnum(FillMode.class),
         buffer.readEnum(LineTieBias.class),
         buffer.readEnum(FaceRasterizationMode.class),
         readPlacementContext(buffer),
         buffer.readBoolean() ? ResourceLocation.parse(buffer.readUtf(128)) : null
      );
   }

   public static BuildingPreviewPayload active(
      List<BlockPos> points,
      Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor,
      RaycastPlacement raycastPlacement,
      boolean ctrlHeld,
      boolean polygonClosed,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      BlockPos freeScrollOffset,
      LineTieBias faceTieBias,
      PlacementContextSnapshot placementContext,
      ResourceLocation activePlacementEffect,
      FastPlaceSettings settings
   ) {
      return new BuildingPreviewPayload(
         true,
         settings.middleConfirmEnabled(),
         true,
         ctrlHeld,
         polygonClosed,
         polygonHeightConfirmed,
         polygonVolumeShape,
         points,
         0,
         freeScrollOffset,
         faceBaseOffset,
         volumeBaseOffset,
         perpendicularAnchor,
         settings.angleDegrees(),
         settings.pointMode(),
         raycastPlacement,
         settings.lineMode(),
         settings.faceMode(),
         settings.volumeMode(),
         settings.fillMode(),
         faceTieBias,
         settings.faceRasterizationMode(),
         placementContext,
         activePlacementEffect
      );
   }

   public static BuildingPreviewPayload inactive(FastPlaceSettings settings) {
      return new BuildingPreviewPayload(
         settings.enabled(),
         settings.middleConfirmEnabled(),
         false,
         false,
         false,
         false,
         PolygonVolumeShape.EXTRUDE,
         List.of(),
         5,
         BlockPos.ZERO,
         Vec3.ZERO,
         Vec3.ZERO,
         BlockPos.ZERO,
         settings.angleDegrees(),
         settings.pointMode(),
         settings.raycastPlacement(),
         settings.lineMode(),
         settings.faceMode(),
         settings.volumeMode(),
         settings.fillMode(),
         LineTieBias.DEFAULT,
         settings.faceRasterizationMode(),
         null,
         null
      );
   }

   public static BuildingPreviewPayload inactive() {
      return new BuildingPreviewPayload(
         true,
         true,
         false,
         false,
         false,
         false,
         PolygonVolumeShape.EXTRUDE,
         List.of(),
         5,
         BlockPos.ZERO,
         Vec3.ZERO,
         Vec3.ZERO,
         BlockPos.ZERO,
         0.0,
         PointMode.RAYCAST,
          RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.POLYGON,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE,
         LineTieBias.DEFAULT,
         FaceRasterizationMode.POINT_SWEEP,
         null,
         null
      );
   }

   /** Returns the interaction state as one cohesive value object. */
   public BuildingPreviewSession session() {
      return new BuildingPreviewSession(
         enabled,
         middleConfirmEnabled,
         active,
         ctrlHeld,
         polygonClosed,
         polygonHeightConfirmed,
         polygonVolumeShape,
         points,
         freeScrollOffset,
         placementContext
      );
   }

   /** Returns the geometry parameters required to reproduce this preview. */
   public BuildingPreviewParameters parameters() {
      return new BuildingPreviewParameters(
         angleDistance,
         faceBaseOffset,
         volumeBaseOffset,
         perpendicularAnchor,
         angleDegrees,
         pointMode,
         raycastPlacement,
         lineMode,
         faceMode,
         volumeMode,
         fillMode,
         faceTieBias,
         faceRasterizationMode
      );
   }

   /** Returns the server-selected effect identity for this preview. */
   public BuildingPreviewEffectSnapshot effect() {
      return new BuildingPreviewEffectSnapshot(activePlacementEffect);
   }

   public FastPlaceGeometry.Modes modes() {
      VolumeMode effectiveVolumeMode = this.polygonClosed ? VolumeMode.PERPENDICULAR_TO_FACE : this.volumeMode;
      return new FastPlaceGeometry.Modes(
         this.pointMode,
         this.raycastPlacement,
         this.lineMode,
         this.faceMode,
         effectiveVolumeMode,
         this.fillMode,
         this.angleDegrees,
         this.ctrlHeld,
         this.faceTieBias,
         this.faceRasterizationMode
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.enabled);
      buffer.writeBoolean(this.middleConfirmEnabled);
      buffer.writeBoolean(this.active);
      buffer.writeBoolean(this.ctrlHeld);
      buffer.writeBoolean(this.polygonClosed);
      buffer.writeBoolean(this.polygonHeightConfirmed);
      buffer.writeEnum(this.polygonVolumeShape);
      buffer.writeCollection(this.points, (writer, point) -> writer.writeBlockPos(point));
      buffer.writeVarInt(this.angleDistance);
      buffer.writeBlockPos(this.freeScrollOffset);
      writeVec3(buffer, this.faceBaseOffset);
      writeVec3(buffer, this.volumeBaseOffset);
      buffer.writeBlockPos(this.perpendicularAnchor);
      buffer.writeDouble(this.angleDegrees);
      buffer.writeEnum(this.pointMode);
      buffer.writeEnum(this.raycastPlacement);
      buffer.writeEnum(this.lineMode);
      buffer.writeEnum(this.faceMode);
      buffer.writeEnum(this.volumeMode);
      buffer.writeEnum(this.fillMode);
      buffer.writeEnum(this.faceTieBias);
      buffer.writeEnum(this.faceRasterizationMode);
      writePlacementContext(buffer, this.placementContext);
      buffer.writeBoolean(this.activePlacementEffect != null);
      if (this.activePlacementEffect != null) {
         buffer.writeUtf(this.activePlacementEffect.toString(), 128);
      }
   }

   @Override
   public Type<BuildingPreviewPayload> type() {
      return TYPE;
   }

   private static Vec3 readVec3(FriendlyByteBuf buffer) {
      return GeometryNumbers.finiteOrZero(new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
   }

   private static void writeVec3(FriendlyByteBuf buffer, Vec3 value) {
      Vec3 clean = GeometryNumbers.finiteOrZero(value);
      buffer.writeDouble(clean.x);
      buffer.writeDouble(clean.y);
      buffer.writeDouble(clean.z);
   }

   private static List<BlockPos> readPoints(FriendlyByteBuf buffer) {
      return buffer.readCollection(
         FriendlyByteBuf.limitValue(size -> new ArrayList<>(size), MAX_PREVIEW_POINTS),
         reader -> reader.readBlockPos()
      );
   }

   private static PlacementContextSnapshot readPlacementContext(FriendlyByteBuf buffer) {
      if (!buffer.readBoolean()) {
         return null;
      }
      BlockPos hitBlock = buffer.readBlockPos();
      Vec3 hitLocation = readVec3(buffer);
      net.minecraft.core.Direction clickedFace = buffer.readEnum(net.minecraft.core.Direction.class);
      boolean inside = buffer.readBoolean();
      boolean replacing = buffer.readBoolean();
      float rotation = buffer.readFloat();
      net.minecraft.core.Direction horizontal = buffer.readEnum(net.minecraft.core.Direction.class);
      net.minecraft.core.Direction vertical = buffer.readEnum(net.minecraft.core.Direction.class);
      List<net.minecraft.core.Direction> nearest = new ArrayList<>(net.minecraft.core.Direction.values().length);
      for (int index = 0; index < net.minecraft.core.Direction.values().length; index++) {
         nearest.add(buffer.readEnum(net.minecraft.core.Direction.class));
      }
      boolean secondary = buffer.readBoolean();
      return new PlacementContextSnapshot(
         hitBlock, hitLocation, clickedFace, inside, replacing, rotation,
         horizontal, vertical, nearest, secondary
      );
   }

   private static void writePlacementContext(FriendlyByteBuf buffer, PlacementContextSnapshot snapshot) {
      buffer.writeBoolean(snapshot != null);
      if (snapshot == null) {
         return;
      }
      buffer.writeBlockPos(snapshot.hitBlock());
      writeVec3(buffer, snapshot.hitLocation());
      buffer.writeEnum(snapshot.clickedFace());
      buffer.writeBoolean(snapshot.inside());
      buffer.writeBoolean(snapshot.replacingClickedBlock());
      buffer.writeFloat(snapshot.rotation());
      buffer.writeEnum(snapshot.horizontalDirection());
      buffer.writeEnum(snapshot.verticalDirection());
      snapshot.nearestDirections().forEach(buffer::writeEnum);
      buffer.writeBoolean(snapshot.secondaryUseActive());
   }
}

