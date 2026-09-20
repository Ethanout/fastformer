package io.github.fastformer.network.payload.preview;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Wire payload for the server-selected preview effect identity. */
public record BuildingPreviewEffectPayload(
   long revision, BuildingPreviewEffectSnapshot value, OperationCallbackScope callbackScope
) implements CustomPacketPayload {
   public static final Type<BuildingPreviewEffectPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "building_preview_effect")
   );
   public static final StreamCodec<FriendlyByteBuf, BuildingPreviewEffectPayload> STREAM_CODEC = CustomPacketPayload.codec(
      BuildingPreviewEffectPayload::write,
      BuildingPreviewEffectPayload::new
   );

   public BuildingPreviewEffectPayload(BuildingPreviewEffectSnapshot value) {
      this(0L, value, OperationCallbackScope.unscoped());
   }

   public BuildingPreviewEffectPayload(long revision, BuildingPreviewEffectSnapshot value) {
      this(revision, value, OperationCallbackScope.unscoped());
   }

   public BuildingPreviewEffectPayload {
      revision = Math.max(0L, revision);
      value = value == null ? new BuildingPreviewEffectSnapshot(null) : value;
      if (callbackScope == null) {
         throw new IllegalArgumentException("Building callback scope is required");
      }
   }

   public BuildingPreviewEffectPayload(ResourceLocation activeEffect) {
      this(0L, new BuildingPreviewEffectSnapshot(activeEffect), OperationCallbackScope.unscoped());
   }

   private BuildingPreviewEffectPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readVarLong(),
         buffer.readBoolean() ? new BuildingPreviewEffectSnapshot(ResourceLocation.parse(buffer.readUtf(128))) : null,
         OperationCallbackScope.STREAM_CODEC.decode(buffer)
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(revision);
      ResourceLocation activeEffect = value.activeEffect();
      buffer.writeBoolean(activeEffect != null);
      if (activeEffect != null) {
         buffer.writeUtf(activeEffect.toString(), 128);
      }
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
   }

   @Override
   public Type<BuildingPreviewEffectPayload> type() {
      return TYPE;
   }
}
