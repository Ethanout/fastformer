package io.github.fastformer.network.payload.preview;

import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ActivityStatePayload(
   long revision, FastPlaceActivity activity, OperationCallbackScope callbackScope
) implements CustomPacketPayload {
   public static final Type<ActivityStatePayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "activity_state")
   );
   public static final StreamCodec<FriendlyByteBuf, ActivityStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      ActivityStatePayload::write, ActivityStatePayload::new
   );

   public ActivityStatePayload {
      revision = Math.max(0L, revision);
      activity = activity == null ? FastPlaceActivity.NONE : activity;
      if (callbackScope == null) {
         throw new IllegalArgumentException("Activity callback scope is required");
      }
   }

   public ActivityStatePayload(FastPlaceActivity activity) {
      this(0L, activity, OperationCallbackScope.unscoped());
   }

   public ActivityStatePayload(long revision, FastPlaceActivity activity) {
      this(revision, activity, OperationCallbackScope.unscoped());
   }

   private ActivityStatePayload(FriendlyByteBuf buffer) {
      this(
         buffer.readVarLong(), buffer.readEnum(FastPlaceActivity.class), OperationCallbackScope.STREAM_CODEC.decode(buffer)
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(this.revision);
      buffer.writeEnum(this.activity);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, this.callbackScope);
   }

   @Override
   public Type<ActivityStatePayload> type() {
      return TYPE;
   }
}
