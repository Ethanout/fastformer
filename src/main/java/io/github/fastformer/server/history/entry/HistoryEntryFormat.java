package io.github.fastformer.server.history.entry;

import io.github.fastformer.core.persist.PayloadOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Converts registered history entries to bytes. Callers must reserve memory before encoding or decoding. */
public final class HistoryEntryFormat<E extends HistoryEntry> {
   private final EntryCodecRegistry entries;
   private final Class<E> acceptedType;

   /** Accept HistoryEntry.class for a mixed-type history, or a subtype for a typed boundary. */
   public HistoryEntryFormat(EntryCodecRegistry entries, Class<E> acceptedType) {
      this.entries = Objects.requireNonNull(entries, "entries");
      this.acceptedType = Objects.requireNonNull(acceptedType, "acceptedType");
   }

   public byte[] encode(E entry, int maxEncodedBytes) throws IOException {
      if (maxEncodedBytes < 1) throw new IllegalArgumentException("Invalid encoded history budget");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxEncodedBytes, 8192));
      try (DataOutputStream output = new DataOutputStream(new PayloadOutputStream(bytes, maxEncodedBytes))) {
         NbtIo.write(entries.encode(entry), output);
      }
      return bytes.toByteArray();
   }

   public E decode(
      HolderLookup.Provider registries, byte[] payload, long maxDecodedBytes
   ) throws IOException {
      if (maxDecodedBytes < 1) throw new IllegalArgumentException("Invalid decoded history budget");
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
         CompoundTag tag;
         try {
            tag = NbtIo.read(input, NbtAccounter.create(maxDecodedBytes));
         } catch (net.minecraft.nbt.NbtAccounterException failure) {
            throw new IOException("History data exceeds its decode memory budget", failure);
         } catch (net.minecraft.ReportedException failure) {
            throw new IOException("History NBT data is malformed", failure);
         }
         if (input.read() != -1) throw new IOException("Trailing bytes in history batch");
         HistoryEntry entry = entries.decode(registries, tag);
         if (!acceptedType.isInstance(entry)) {
            throw new IOException("History entry is outside the configured type boundary: " + entry.type());
         }
         return acceptedType.cast(entry);
      }
   }

}
