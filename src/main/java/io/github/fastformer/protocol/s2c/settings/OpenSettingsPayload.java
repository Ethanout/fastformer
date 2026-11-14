package io.github.fastformer.protocol.s2c.settings;

import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import io.github.fastformer.common.history.HistoryCompressionLimits;

public record OpenSettingsPayload(
   boolean middleConfirmEnabled,
   OperationConflictMode placementConflictMode,
   PlacementUpdateMode placementUpdateMode,
   List<ResourceLocation> enabledPlacementEffects,
   boolean emptyHandWrench,
   boolean fallingDisabled,
   int worldUndoHistoryLimit,
   int sessionUndoHistoryLimit,
   HistoryCompressionLimits tinkerHistoryMergeLimits,
   boolean skipRepeatedCropStages,
   boolean vanillaBlockHistory
) implements CustomPacketPayload {
   public static final Type<OpenSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "open_settings"));
   public static final StreamCodec<FriendlyByteBuf, OpenSettingsPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OpenSettingsPayload::write, OpenSettingsPayload::new
   );

   private OpenSettingsPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(),
         buffer.readEnum(OperationConflictMode.class), buffer.readEnum(PlacementUpdateMode.class),
         readEffectIds(buffer), buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(),
         new HistoryCompressionLimits(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()),
         buffer.readBoolean(), buffer.readBoolean()
      );
   }

   public OpenSettingsPayload {
      placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.CLIENT_ONLY : placementUpdateMode;
      enabledPlacementEffects = enabledPlacementEffects == null ? List.of() : List.copyOf(enabledPlacementEffects);
      worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
      tinkerHistoryMergeLimits = tinkerHistoryMergeLimits == null ? HistoryCompressionLimits.DEFAULTS : tinkerHistoryMergeLimits;
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.middleConfirmEnabled);
      buffer.writeEnum(this.placementConflictMode);
      buffer.writeEnum(this.placementUpdateMode);
      buffer.writeVarInt(this.enabledPlacementEffects.size());
      this.enabledPlacementEffects.forEach(id -> buffer.writeUtf(id.toString(), 128));
      buffer.writeBoolean(this.emptyHandWrench);
      buffer.writeBoolean(this.fallingDisabled);
      buffer.writeVarInt(this.worldUndoHistoryLimit);
      buffer.writeVarInt(this.sessionUndoHistoryLimit);
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.recentAfter());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.recentGroup());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.olderAfter());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.olderGroup());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.oldestAfter());
      buffer.writeVarInt(this.tinkerHistoryMergeLimits.oldestGroup());
      buffer.writeBoolean(this.skipRepeatedCropStages);
      buffer.writeBoolean(this.vanillaBlockHistory);
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
