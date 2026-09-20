package io.github.fastformer.network.payload.operation;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Identifies the player, world, and client session that own a server callback. */
public record OperationCallbackScope(UUID playerId, ResourceLocation dimension, UUID sessionId) {
   private static final OperationCallbackScope UNSCOPED = new OperationCallbackScope(
      new UUID(0L, 0L), ResourceLocation.fromNamespaceAndPath("fastformer", "unscoped"), new UUID(0L, 0L)
   );
   public static final StreamCodec<FriendlyByteBuf, OperationCallbackScope> STREAM_CODEC =
      StreamCodec.ofMember(OperationCallbackScope::write, OperationCallbackScope::read);

   public OperationCallbackScope {
      if (playerId == null || dimension == null || sessionId == null) {
         throw new IllegalArgumentException("Callback scope requires a player, dimension, and session");
      }
   }

   /** Supplies a scope for local snapshots that never cross the network. */
   public static OperationCallbackScope unscoped() {
      return UNSCOPED;
   }

   private static OperationCallbackScope read(FriendlyByteBuf buffer) {
      return new OperationCallbackScope(buffer.readUUID(), buffer.readResourceLocation(), buffer.readUUID());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(playerId);
      buffer.writeResourceLocation(dimension);
      buffer.writeUUID(sessionId);
   }
}
