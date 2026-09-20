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
