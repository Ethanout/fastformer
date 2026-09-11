package io.github.fastformer.fastplace.history;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Immutable undo and redo ordering metadata. Batch contents live in separate files. */
public record HistoryOrderIndex(List<UUID> undo, List<UUID> redo) {
   public HistoryOrderIndex {
      undo = List.copyOf(undo);
      redo = List.copyOf(redo);
   }

   static byte[] encode(HistoryOrderIndex index) {
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream(encodedBytes(index));
         DataOutputStream out = new DataOutputStream(bytes);
         writeIds(out, index.undo);
         writeIds(out, index.redo);
         out.flush();
         return bytes.toByteArray();
      } catch (IOException impossible) {
         throw new AssertionError(impossible);
      }
   }

   static HistoryOrderIndex decode(byte[] payload, int maxEntries) throws IOException {
      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
         int undoCount = readCount(in, maxEntries);
         List<UUID> undo = readIds(in, undoCount);
         int redoCount = readCount(in, maxEntries - undoCount);
         List<UUID> redo = readIds(in, redoCount);
         if (in.available() != 0) throw new IOException("Trailing history index data");
         return new HistoryOrderIndex(undo, redo);
      } catch (EOFException ex) {
         throw new IOException("Truncated history index", ex);
      }
   }

   static int encodedBytes(HistoryOrderIndex index) {
      return Math.addExact(2 * Integer.BYTES, Math.multiplyExact(index.undo.size() + index.redo.size(), 2 * Long.BYTES));
   }

   static long maximumEncodedBytes(int maxEntries) {
      return Math.addExact(2L * Integer.BYTES, Math.multiplyExact((long) maxEntries, 2L * Long.BYTES));
   }

   private static void writeIds(DataOutputStream out, List<UUID> ids) throws IOException {
      out.writeInt(ids.size());
      for (UUID id : ids) {
         out.writeLong(id.getMostSignificantBits());
         out.writeLong(id.getLeastSignificantBits());
      }
   }

   private static int readCount(DataInputStream in, int remainingEntries) throws IOException {
      int count = in.readInt();
      if (count < 0 || count > remainingEntries) throw new IOException("Invalid history index size");
      return count;
   }

   private static List<UUID> readIds(DataInputStream in, int count) throws IOException {
      if (count > in.available() / (2 * Long.BYTES)) {
         throw new IOException("Truncated history index entries");
      }
      UUID[] ids = new UUID[count];
      for (int i = 0; i < count; i++) ids[i] = new UUID(in.readLong(), in.readLong());
      return List.of(ids);
   }
}
