package io.github.fastformer.server.history.entry;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class BlockEditHistoryMetadataTest {
   @Test
   void metadataRequiresAnExplicitValidCompressionStage() throws Exception {
      for (int stage = 0; stage <= 3; stage++) {
         var root = new CompoundTag();
         var metadata = new BlockEditHistoryMetadata("same", 8, stage);
         root.put("Tinker", metadata.encode());
         assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
      }
      var root = new CompoundTag();
      root.put("Tinker", new BlockEditHistoryMetadata("same", 8, 2).encode());
      root.getCompound("Tinker").remove("CompressionStage");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("Actions", 1);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("CompressionStage", 0);
      root.getCompound("Tinker").putInt("Actions", 0);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putInt("Actions", 1);
      root.getCompound("Tinker").putInt("CompressionStage", 4);
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.getCompound("Tinker").putString("CompressionStage", "bad");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
      root.putString("Tinker", "invalid");
      assertThrows(java.io.IOException.class, () -> BlockEditHistoryMetadata.decode(root));
   }

   @Test
   void largeActionCountSurvivesRoundTrip() throws Exception {
      var metadata = new BlockEditHistoryMetadata("same", 80000, 3);
      var root = new CompoundTag();
      root.put("Tinker", metadata.encode());
      assertEquals(metadata, BlockEditHistoryMetadata.decode(root));
   }
}
