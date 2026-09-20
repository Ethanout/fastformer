package io.github.fastformer.network.payload.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Answers the outcome of the workspace transfers that a client asked about.
 *
 * <p>The answer repeats the dimension of the query. The client drops an answer whose
 * dimension does not match the receipt, so a result never settles a submission of
 * another environment.</p>
 */
public record OperationWorkspaceReceiptPayload(ResourceLocation dimension, List<Entry> entries)
   implements CustomPacketPayload {
   /** Entries that one answer may carry. */
   public static final int MAX_ENTRIES = 64;
   public static final Type<OperationWorkspaceReceiptPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_receipt")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceReceiptPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceReceiptPayload::write, OperationWorkspaceReceiptPayload::new);

   public OperationWorkspaceReceiptPayload {
      if (dimension == null) {
         throw new IllegalArgumentException("Workspace receipt answer needs a dimension");
      }
      if (entries == null || entries.size() > MAX_ENTRIES) {
         throw new IllegalArgumentException("Invalid workspace receipt answer size");
      }
      entries = List.copyOf(entries);
   }

   /** One answered transfer. */
   public record Entry(UUID transferId, OperationSubmissionOutcome outcome) {
      public Entry {
         if (transferId == null || outcome == null || !outcome.reportable()) {
            throw new IllegalArgumentException("A receipt answer needs a transfer id and a reportable outcome");
         }
      }
   }

   private OperationWorkspaceReceiptPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readResourceLocation(),
         buffer.readCollection(
            FriendlyByteBuf.limitValue(ArrayList::new, MAX_ENTRIES), OperationWorkspaceReceiptPayload::readEntry
         )
      );
   }

   private static Entry readEntry(FriendlyByteBuf buffer) {
      UUID transferId = buffer.readUUID();
      int ordinal = buffer.readVarInt();
      OperationSubmissionOutcome[] values = OperationSubmissionOutcome.values();
      if (ordinal < 0 || ordinal >= values.length) {
         throw new IllegalArgumentException("Invalid workspace receipt outcome");
      }
      return new Entry(transferId, values[ordinal]);
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeCollection(this.entries, (buf, entry) -> {
         buf.writeUUID(entry.transferId());
         buf.writeVarInt(entry.outcome().ordinal());
      });
   }

   @Override
   public Type<OperationWorkspaceReceiptPayload> type() {
      return TYPE;
   }
}
