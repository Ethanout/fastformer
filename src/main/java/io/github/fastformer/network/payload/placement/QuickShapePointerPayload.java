package io.github.fastformer.network.payload.placement;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** A pointer action addresses the draft and candidate seen at physical press time. */
public record QuickShapePointerPayload(long revision, OperationCallbackScope scope, Action action,
   BlockPos candidate, Vec3 eye, Vec3 view, boolean modifierHeld, long requestId) implements CustomPacketPayload {
   public enum Action { POINT, CLOSE, MIDDLE, UNDO }

   public static final Type<QuickShapePointerPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_shape_pointer"));
   public static final StreamCodec<FriendlyByteBuf, QuickShapePointerPayload> STREAM_CODEC =
      CustomPacketPayload.codec(QuickShapePointerPayload::write, QuickShapePointerPayload::new);

   public QuickShapePointerPayload {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(action);
      candidate = Objects.requireNonNull(candidate).immutable();
      Objects.requireNonNull(eye);
      Objects.requireNonNull(view);
      if (revision <= 0 || scope.equals(OperationCallbackScope.unscoped())
         || !finite(eye) || !finite(view) || Math.abs(view.lengthSqr() - 1.0) > 0.001
         || requestId <= 0) {
         throw new IllegalArgumentException("Invalid quick-shape pointer snapshot");
      }
   }

   private static boolean finite(Vec3 value) {
      return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   private QuickShapePointerPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer), buffer.readEnum(Action.class),
         buffer.readBlockPos(), buffer.readVec3(), buffer.readVec3(), buffer.readBoolean(), buffer.readVarLong());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, scope);
      buffer.writeEnum(action);
      buffer.writeBlockPos(candidate);
      buffer.writeVec3(eye);
      buffer.writeVec3(view);
      buffer.writeBoolean(modifierHeld);
      buffer.writeVarLong(requestId);
   }

   public boolean matches(long currentRevision, OperationCallbackScope currentScope) {
      return revision == currentRevision && scope.equals(currentScope);
   }

   @Override
   public Type<QuickShapePointerPayload> type() { return TYPE; }
}
