package io.github.fastformer.client.operation.model;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSelectionPartMaskTest {
   @Test
   void onlyTransformedOrPendingDeleteWorldPartsMaskSources() {
      BlockPos source = new BlockPos(1, 2, 3);
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(source, source, source, source);
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      assertFalse(part.masksSourceBlocks());
      assertTrue(part.withTranslation(new BlockPos(1, 0, 0)).masksSourceBlocks());
      assertTrue(part.withPendingDelete(true).masksSourceBlocks());
      assertFalse(new ClientSelectionPart(
         1, ClientSelectionPart.Source.CLIPBOARD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      ).withTranslation(new BlockPos(1, 0, 0)).masksSourceBlocks());
   }
}
