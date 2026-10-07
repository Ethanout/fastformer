package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.geometry.generation.FaceRasterizationMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;

public record OpenSettingsPayload(
   boolean middleConfirmEnabled,
   FaceRasterizationMode faceRasterizationMode,
   OperationConflictMode placementConflictMode,
   PlacementUpdateMode placementUpdateMode,
   List<ResourceLocation> enabledPlacementEffects,
   boolean emptyHandWrench,
   boolean fallingDisabled,
   int worldUndoHistoryLimit,
   int sessionUndoHistoryLimit,
   TinkerHistoryMergeLimits tinkerHistoryMergeLimits
) implements CustomPacketPayload {
   public static final Type<OpenSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "open_settings"));
   public static final StreamCodec<FriendlyByteBuf, OpenSettingsPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OpenSettingsPayload::write, OpenSettingsPayload::new
   );

   private OpenSettingsPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(), buffer.readEnum(FaceRasterizationMode.class),
         buffer.readEnum(OperationConflictMode.class), buffer.readEnum(PlacementUpdateMode.class),
         readEffectIds(buffer), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
         new TinkerHistoryMergeLimits(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt())
      );
   }

   public OpenSettingsPayload {
      faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.DEFAULT
         : faceRasterizationMode;
      placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.CLIENT_ONLY : placementUpdateMode;
      enabledPlacementEffects = enabledPlacementEffects == null ? List.of() : List.copyOf(enabledPlacementEffects);
      worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
      tinkerHistoryMergeLimits = tinkerHistoryMergeLimits == null ? TinkerHistoryMergeLimits.DEFAULTS : tinkerHistoryMergeLimits;
   }

   public OpenSettingsPayload(boolean middleConfirmEnabled, FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode, PlacementUpdateMode placementUpdateMode,
      List<ResourceLocation> enabledPlacementEffects, boolean emptyHandWrench, boolean fallingDisabled,
      int worldUndoHistoryLimit, int sessionUndoHistoryLimit) {
      this(middleConfirmEnabled, faceRasterizationMode, placementConflictMode, placementUpdateMode,
         enabledPlacementEffects, emptyHandWrench, fallingDisabled, worldUndoHistoryLimit, sessionUndoHistoryLimit,
         TinkerHistoryMergeLimits.DEFAULTS);
   }

   public OpenSettingsPayload(boolean middleConfirmEnabled) {
      this(middleConfirmEnabled, FaceRasterizationMode.DEFAULT,
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
      buffer.writeBoolean(this.fallingDisabled);
      buffer.writeVarInt(this.worldUndoHistoryLimit);
      buffer.writeVarInt(this.sessionUndoHistoryLimit);
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.near());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.middle());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.far());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.nearDistance());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.farDistance());
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
