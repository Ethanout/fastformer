package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.PolygonVolumeShape;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Wire payload for the mutable interaction session portion of a building preview. */
public record BuildingPreviewSessionPayload(long revision, BuildingPreviewSession value) implements CustomPacketPayload {
   public static final Type<BuildingPreviewSessionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "building_preview_session")
   );
   public static final StreamCodec<FriendlyByteBuf, BuildingPreviewSessionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      BuildingPreviewSessionPayload::write,
      BuildingPreviewSessionPayload::new
   );

   public BuildingPreviewSessionPayload(BuildingPreviewSession value) {
      this(0L, value);
   }

   public BuildingPreviewSessionPayload(long revision, BuildingPreviewSession value) {
      this.revision = Math.max(0L, revision);
      this.value = value == null ? inactive() : value;
   }

   public BuildingPreviewSessionPayload(
      boolean enabled,
      boolean middleConfirmEnabled,
      boolean active,
      boolean ctrlHeld,
      boolean polygonClosed,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> points,
      BlockPos freeScrollOffset,
      io.github.fastformer.fastplace.PlacementContextSnapshot placementContext
   ) {
      this(0L, new BuildingPreviewSession(
         enabled, middleConfirmEnabled, active, ctrlHeld, polygonClosed,
         polygonHeightConfirmed, polygonVolumeShape, points, freeScrollOffset, placementContext
      ));
   }

   private BuildingPreviewSessionPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), new BuildingPreviewSession(
         buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
         buffer.readBoolean(), buffer.readBoolean(), buffer.readEnum(PolygonVolumeShape.class),
         BuildingPreviewCodec.readPoints(buffer), buffer.readBlockPos(),
         BuildingPreviewCodec.readPlacementContext(buffer)
      ));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(revision);
      buffer.writeBoolean(value.enabled());
      buffer.writeBoolean(value.middleConfirmEnabled());
      buffer.writeBoolean(value.active());
      buffer.writeBoolean(value.ctrlHeld());
      buffer.writeBoolean(value.polygonClosed());
      buffer.writeBoolean(value.polygonHeightConfirmed());
      buffer.writeEnum(value.polygonVolumeShape());
      BuildingPreviewCodec.writePoints(buffer, value.points());
      buffer.writeBlockPos(value.freeScrollOffset());
      BuildingPreviewCodec.writePlacementContext(buffer, value.placementContext());
   }

   private static BuildingPreviewSession inactive() {
      return new BuildingPreviewSession(
         true, true, false, false, false, false, PolygonVolumeShape.EXTRUDE,
         List.of(), BlockPos.ZERO, null
      );
   }

   @Override
   public Type<BuildingPreviewSessionPayload> type() {
      return TYPE;
   }
}
