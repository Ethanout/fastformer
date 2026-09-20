package io.github.fastformer.network.payload.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Asks the server for the outcome of the workspace transfers that this client sent.
 *
 * <p>The query carries the dimension of the receipts. A submission belongs to one
 * environment, so an answer for another environment must not settle it.</p>
 */
public record OperationWorkspaceReceiptQueryPayload(ResourceLocation dimension, List<UUID> transferIds)
   implements CustomPacketPayload {
   /** Transfers that one query may carry. A larger set needs more than one query. */
   public static final int MAX_TRANSFER_IDS = 64;
   public static final Type<OperationWorkspaceReceiptQueryPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_receipt_query")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceReceiptQueryPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceReceiptQueryPayload::write, OperationWorkspaceReceiptQueryPayload::new);

   public OperationWorkspaceReceiptQueryPayload {
      if (dimension == null) {
         throw new IllegalArgumentException("Workspace receipt query needs a dimension");
      }
      if (transferIds == null || transferIds.isEmpty() || transferIds.size() > MAX_TRANSFER_IDS) {
         throw new IllegalArgumentException("Invalid workspace receipt query size");
      }
      if (transferIds.stream().anyMatch(java.util.Objects::isNull)) {
         throw new IllegalArgumentException("Workspace receipt query holds a null transfer id");
      }
      transferIds = List.copyOf(transferIds);
   }

   private OperationWorkspaceReceiptQueryPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readResourceLocation(),
         buffer.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, MAX_TRANSFER_IDS), input -> input.readUUID())
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeResourceLocation(this.dimension);
      buffer.writeCollection(this.transferIds, (buf, id) -> buf.writeUUID(id));
   }

   @Override
   public Type<OperationWorkspaceReceiptQueryPayload> type() {
      return TYPE;
   }
}
