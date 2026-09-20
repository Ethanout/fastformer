package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public record OpenSettingsPayload(
   boolean middleConfirmEnabled,
   FaceRasterizationMode faceRasterizationMode,
   OperationConflictMode placementConflictMode,
   PlacementUpdateMode placementUpdateMode,
   List<ResourceLocation> enabledPlacementEffects,
   boolean emptyHandWrench,
   boolean globalFrozen,
   int worldUndoHistoryLimit,
   int sessionUndoHistoryLimit
) implements CustomPacketPayload {
   public static final Type<OpenSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "open_settings"));
   public static final StreamCodec<FriendlyByteBuf, OpenSettingsPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OpenSettingsPayload::write, OpenSettingsPayload::new
   );

   private OpenSettingsPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(), buffer.readEnum(FaceRasterizationMode.class),
         buffer.readEnum(OperationConflictMode.class), buffer.readEnum(PlacementUpdateMode.class),
         readEffectIds(buffer), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt()
      );
   }

   public OpenSettingsPayload {
      faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : faceRasterizationMode;
      placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.CLIENT_ONLY : placementUpdateMode;
      enabledPlacementEffects = enabledPlacementEffects == null ? List.of() : List.copyOf(enabledPlacementEffects);
      worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
   }

   public OpenSettingsPayload(boolean middleConfirmEnabled) {
      this(middleConfirmEnabled, FaceRasterizationMode.POINT_SWEEP,
         OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, List.of(), true, false, 200, 100);
   }

   public OpenSettingsPayload(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      this(
         middleConfirmEnabled, faceRasterizationMode, placementConflictMode,
         placementUpdateMode, List.of(), true, false, worldUndoHistoryLimit, sessionUndoHistoryLimit
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.middleConfirmEnabled);
      buffer.writeEnum(this.faceRasterizationMode);
      buffer.writeEnum(this.placementConflictMode);
      buffer.writeEnum(this.placementUpdateMode);
      buffer.writeVarInt(this.enabledPlacementEffects.size());
      this.enabledPlacementEffects.forEach(id -> buffer.writeUtf(id.toString(), 128));
      buffer.writeBoolean(this.emptyHandWrench);
      buffer.writeBoolean(this.globalFrozen);
      buffer.writeVarInt(this.worldUndoHistoryLimit);
      buffer.writeVarInt(this.sessionUndoHistoryLimit);
   }

   @Override
   public Type<OpenSettingsPayload> type() {
      return TYPE;
   }

   private static List<ResourceLocation> readEffectIds(FriendlyByteBuf buffer) {
      int count = buffer.readVarInt();
      if (count < 0 || count > 256) {
         throw new IllegalArgumentException("Invalid placement effect count: " + count);
      }
      List<ResourceLocation> result = new ArrayList<>(count);
      for (int index = 0; index < count; index++) {
         result.add(ResourceLocation.parse(buffer.readUtf(128)));
      }
      return List.copyOf(result);
   }
}
