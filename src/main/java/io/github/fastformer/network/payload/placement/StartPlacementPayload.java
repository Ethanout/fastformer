package io.github.fastformer.network.payload.placement;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Starts a building session from the target observed at physical press time. */
public record StartPlacementPayload(long requestId, Target target) implements CustomPacketPayload {
   public static final Type<StartPlacementPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "start_placement")
   );
   public static final StreamCodec<FriendlyByteBuf, StartPlacementPayload> STREAM_CODEC = CustomPacketPayload.codec(
      StartPlacementPayload::write, StartPlacementPayload::new
   );

   public StartPlacementPayload {
      if (requestId <= 0L || target == null) {
         throw new IllegalArgumentException("A start placement request needs an id and target");
      }
   }

   private StartPlacementPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), Target.read(buffer));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      target.write(buffer);
   }

   @Override
   public Type<StartPlacementPayload> type() {
      return TYPE;
   }

   /** The inactive preview identity and exact raycast target captured by the client. */
   public record Target(long revision, OperationCallbackScope callbackScope, RaycastPlacement placement,
      BlockHitResult hit, Vec3 eye, Vec3 view) {
      public Target {
         Objects.requireNonNull(callbackScope);
         Objects.requireNonNull(placement);
         Objects.requireNonNull(hit);
         Objects.requireNonNull(eye);
         Objects.requireNonNull(view);
         if (revision <= 0L || callbackScope.equals(OperationCallbackScope.unscoped())
            || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
            || !finite(hit.getLocation()) || !finite(eye) || !finite(view) || Math.abs(view.lengthSqr() - 1.0) > 0.001) {
            throw new IllegalArgumentException("Invalid captured placement target");
         }
         hit = immutable(hit);
      }

      private static Target read(FriendlyByteBuf buffer) {
         long revision = buffer.readVarLong();
         OperationCallbackScope scope = OperationCallbackScope.STREAM_CODEC.decode(buffer);
         RaycastPlacement placement = buffer.readEnum(RaycastPlacement.class);
         BlockPos position = buffer.readBlockPos();
         Vec3 location = buffer.readVec3();
         Direction direction = buffer.readEnum(Direction.class);
         boolean inside = buffer.readBoolean();
         Vec3 eye = buffer.readVec3();
         Vec3 view = buffer.readVec3();
         return new Target(revision, scope, placement, new BlockHitResult(location, direction, position, inside), eye, view);
      }

      private void write(FriendlyByteBuf buffer) {
         buffer.writeVarLong(revision);
         OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
         buffer.writeEnum(placement);
         buffer.writeBlockPos(hit.getBlockPos());
         buffer.writeVec3(hit.getLocation());
         buffer.writeEnum(hit.getDirection());
         buffer.writeBoolean(hit.isInside());
         buffer.writeVec3(eye);
         buffer.writeVec3(view);
      }

      private static BlockHitResult immutable(BlockHitResult hit) {
         return new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside());
      }

      private static boolean finite(Vec3 value) {
         return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
      }
   }
}
