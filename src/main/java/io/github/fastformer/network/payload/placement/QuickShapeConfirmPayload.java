package io.github.fastformer.network.payload.placement;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Confirms one complete authoritative quick-shape snapshot, never a newer draft. */
public record QuickShapeConfirmPayload(long requestId, long revision, OperationCallbackScope scope)
   implements CustomPacketPayload {
   public static final Type<QuickShapeConfirmPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_shape_confirm")
   );
   public static final StreamCodec<FriendlyByteBuf, QuickShapeConfirmPayload> STREAM_CODEC =
      CustomPacketPayload.codec(QuickShapeConfirmPayload::write, QuickShapeConfirmPayload::new);

   public QuickShapeConfirmPayload {
      if (requestId <= 0 || revision <= 0 || scope == null || scope.equals(OperationCallbackScope.unscoped())) {
         throw new IllegalArgumentException("Quick-shape confirmation requires a request and an authoritative snapshot");
      }
   }

   private QuickShapeConfirmPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, scope);
   }

   public boolean matches(long currentRevision, OperationCallbackScope currentScope) {
      return revision == currentRevision && scope.equals(currentScope);
   }

   @Override
   public Type<QuickShapeConfirmPayload> type() {
      return TYPE;
   }
}
