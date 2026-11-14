package io.github.fastformer.client.preview.block;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockPreviewModeTest {
   @Test void emptyInputUsesTheSharedEmptyModel() {
      var mode = new BlockPreviewMode(0.55F);
      assertSame(BlockPreviewModel.EMPTY, mode.buildModel(BlockPreviewInput.EMPTY));
      assertSame(BlockPreviewModel.EMPTY, mode.buildModel(new BlockPreviewInput(Map.of())));
   }

   @Test void invalidOpacityIsRejected() {
      assertThrows(IllegalArgumentException.class, () -> new BlockPreviewMode(Float.NaN));
      assertThrows(IllegalArgumentException.class, () -> new BlockPreviewMode(-0.1F));
      assertThrows(IllegalArgumentException.class, () -> new BlockPreviewMode(1.1F));
   }
}
