package io.github.fastformer.client.operation.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationClipboardStoreTest {
   @Test
   void staleSaveCannotReplaceANewerFileOrRecreateADeletedDraft() throws Exception {
      Path file = directory.resolve("owned.nbt.gz");
      Object old = OperationClipboardStore.reserve(file);
      CompoundTag newer = new CompoundTag();
      newer.putString("Marker", "new");
      OperationClipboardStore.save(file, newer);
      assertThrows(IOException.class, () -> OperationClipboardStore.save(file, new CompoundTag(), old));
      assertEquals("new", OperationClipboardStore.loadStrict(file).orElseThrow().getString("Marker"));
      Object pending = OperationClipboardStore.reserve(file);
      OperationClipboardStore.delete(file);
      assertThrows(IOException.class, () -> OperationClipboardStore.save(file, newer, pending));
      assertTrue(Files.notExists(file));
   }
   @TempDir
   Path directory;

   @Test
   void compressedClipboardRoundTripsAcrossStoreInstances() throws Exception {
      Path file = this.directory.resolve("clipboard.nbt.gz");
      CompoundTag tag = new CompoundTag();
      tag.putInt("Version", 1);
      tag.putString("Marker", "persistent");

      OperationClipboardStore.save(file, tag);

      assertEquals("persistent", OperationClipboardStore.load(file).orElseThrow().getString("Marker"));
      assertTrue(Files.notExists(file.resolveSibling("clipboard.nbt.gz.tmp")));
   }

   @Test
   void corruptClipboardIsPreservedAndReportedAsUnreadable() throws Exception {
      Path file = this.directory.resolve("clipboard.nbt.gz");
      Files.write(file, new byte[]{1, 2, 3, 4});

      assertTrue(OperationClipboardStore.load(file).isEmpty());
      assertEquals(4L, Files.size(file));
   }

   @Test
   void strictLoadReportsCorruptDurableData() throws Exception {
      Path file = this.directory.resolve("draft.nbt.gz");
      Files.write(file, new byte[]{1, 2, 3, 4});

      assertThrows(IOException.class, () -> OperationClipboardStore.loadStrict(file));
      assertEquals(4L, Files.size(file));
   }
}
