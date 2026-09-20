package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Authoritative submission policy paired with one complete building draft. */
public record QuickShapeSubmissionParametersPayload(
   long revision, int maxPlacement, BlockState prototype, LineTieBias faceTieBias, OperationCallbackScope callbackScope
) implements CustomPacketPayload {
   public static final Type<QuickShapeSubmissionParametersPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_shape_submission_parameters")
   );
   public static final StreamCodec<FriendlyByteBuf, QuickShapeSubmissionParametersPayload> STREAM_CODEC =
      CustomPacketPayload.codec(QuickShapeSubmissionParametersPayload::write, QuickShapeSubmissionParametersPayload::new);

   public QuickShapeSubmissionParametersPayload {
      if (revision <= 0 || maxPlacement < 1 || maxPlacement > 20972152 || prototype == null
         || faceTieBias == null || callbackScope == null) {
         throw new IllegalArgumentException("Quick-shape submission parameters are incomplete");
      }
   }

   private QuickShapeSubmissionParametersPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarInt(), Block.stateById(buffer.readVarInt()),
         buffer.readEnum(LineTieBias.class), OperationCallbackScope.STREAM_CODEC.decode(buffer));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(revision);
      buffer.writeVarInt(maxPlacement);
      buffer.writeVarInt(Block.getId(prototype));
      buffer.writeEnum(faceTieBias);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
   }

   @Override
   public Type<QuickShapeSubmissionParametersPayload> type() { return TYPE; }
}
