package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Wire payload for geometry and interaction parameters of a building preview. */
public record BuildingPreviewParametersPayload(
   long revision, BuildingPreviewParameters value, OperationCallbackScope callbackScope
) implements CustomPacketPayload {
   public static final Type<BuildingPreviewParametersPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "building_preview_parameters")
   );
   public static final StreamCodec<FriendlyByteBuf, BuildingPreviewParametersPayload> STREAM_CODEC = CustomPacketPayload.codec(
      BuildingPreviewParametersPayload::write,
      BuildingPreviewParametersPayload::new
   );

   public BuildingPreviewParametersPayload(BuildingPreviewParameters value) {
      this(0L, value, OperationCallbackScope.unscoped());
   }

   public BuildingPreviewParametersPayload(long revision, BuildingPreviewParameters value) {
      this(revision, value, OperationCallbackScope.unscoped());
   }

   public BuildingPreviewParametersPayload {
      revision = Math.max(0L, revision);
      value = value == null ? inactive() : value;
      if (callbackScope == null) {
         throw new IllegalArgumentException("Building callback scope is required");
      }
   }

   public BuildingPreviewParametersPayload(
      int angleDistance,
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
      FaceRasterizationMode faceRasterizationMode
   ) {
      this(0L, new BuildingPreviewParameters(
         angleDistance, faceBaseOffset, volumeBaseOffset, perpendicularAnchor, angleDegrees,
         pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode,
         faceTieBias, faceRasterizationMode
      ), OperationCallbackScope.unscoped());
   }

   private BuildingPreviewParametersPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), new BuildingPreviewParameters(
         buffer.readVarInt(), BuildingPreviewCodec.readVec3(buffer), BuildingPreviewCodec.readVec3(buffer),
         buffer.readBlockPos(), buffer.readDouble(), buffer.readEnum(PointMode.class),
         buffer.readEnum(RaycastPlacement.class), buffer.readEnum(LineMode.class),
         buffer.readEnum(FaceMode.class), buffer.readEnum(VolumeMode.class), buffer.readEnum(FillMode.class),
         buffer.readEnum(LineTieBias.class), buffer.readEnum(FaceRasterizationMode.class)
      ), OperationCallbackScope.STREAM_CODEC.decode(buffer));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(revision);
      buffer.writeVarInt(value.angleDistance());
      BuildingPreviewCodec.writeVec3(buffer, value.faceBaseOffset());
      BuildingPreviewCodec.writeVec3(buffer, value.volumeBaseOffset());
      buffer.writeBlockPos(value.perpendicularAnchor());
      buffer.writeDouble(value.angleDegrees());
      buffer.writeEnum(value.pointMode());
      buffer.writeEnum(value.raycastPlacement());
      buffer.writeEnum(value.lineMode());
      buffer.writeEnum(value.faceMode());
      buffer.writeEnum(value.volumeMode());
      buffer.writeEnum(value.fillMode());
      buffer.writeEnum(value.faceTieBias());
      buffer.writeEnum(value.faceRasterizationMode());
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
   }

   private static BuildingPreviewParameters inactive() {
      return new BuildingPreviewParameters(
         5, Vec3.ZERO, Vec3.ZERO, BlockPos.ZERO, 0.0, PointMode.RAYCAST,
         RaycastPlacement.SURFACE, LineMode.AXIS, FaceMode.POLYGON,
         VolumeMode.PERPENDICULAR_TO_FACE, FillMode.OUTLINE,
         LineTieBias.DEFAULT, FaceRasterizationMode.POINT_SWEEP
      );
   }

   @Override
   public Type<BuildingPreviewParametersPayload> type() {
      return TYPE;
   }
}
