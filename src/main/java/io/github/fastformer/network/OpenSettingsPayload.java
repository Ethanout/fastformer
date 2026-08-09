package io.github.fastformer.network;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.RaycastPlacement;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OpenSettingsPayload(
   boolean middleConfirmEnabled,
   FaceRasterizationMode faceRasterizationMode,
   RaycastPlacement raycastPlacement,
   OperationConflictMode placementConflictMode,
   PlacementUpdateMode placementUpdateMode,
   boolean smartWoodFrame,
   int worldUndoHistoryLimit,
   int sessionUndoHistoryLimit
) implements CustomPacketPayload {
   public static final Type<OpenSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "open_settings"));
   public static final StreamCodec<FriendlyByteBuf, OpenSettingsPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OpenSettingsPayload::write, OpenSettingsPayload::new
   );

   private OpenSettingsPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(), buffer.readEnum(FaceRasterizationMode.class), buffer.readEnum(RaycastPlacement.class),
         buffer.readEnum(OperationConflictMode.class), buffer.readEnum(PlacementUpdateMode.class), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt()
      );
   }

   public OpenSettingsPayload {
      faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : faceRasterizationMode;
      raycastPlacement = raycastPlacement == null ? RaycastPlacement.EMBEDDED : raycastPlacement;
      placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.NORMAL : placementUpdateMode;
      worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
   }

   public OpenSettingsPayload(boolean middleConfirmEnabled) {
      this(middleConfirmEnabled, FaceRasterizationMode.POINT_SWEEP, RaycastPlacement.EMBEDDED, OperationConflictMode.REPLACE, PlacementUpdateMode.NORMAL, true, 200, 100);
   }

   public OpenSettingsPayload(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      RaycastPlacement raycastPlacement,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      this(
         middleConfirmEnabled, faceRasterizationMode, raycastPlacement, placementConflictMode,
         placementUpdateMode, true, worldUndoHistoryLimit, sessionUndoHistoryLimit
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.middleConfirmEnabled);
      buffer.writeEnum(this.faceRasterizationMode);
      buffer.writeEnum(this.raycastPlacement);
      buffer.writeEnum(this.placementConflictMode);
      buffer.writeEnum(this.placementUpdateMode);
      buffer.writeBoolean(this.smartWoodFrame);
      buffer.writeVarInt(this.worldUndoHistoryLimit);
      buffer.writeVarInt(this.sessionUndoHistoryLimit);
   }

   @Override
   public Type<OpenSettingsPayload> type() {
      return TYPE;
   }
}
