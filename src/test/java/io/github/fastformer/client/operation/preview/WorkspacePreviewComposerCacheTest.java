package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class WorkspacePreviewComposerCacheTest {
   @BeforeEach
   void clearGeometryCache() {
      WorkspacePreviewComposer.invalidatePartGeometry();
   }

   @Test
   void frameLookupAlsoCachesTheMatchingResolvedValues() {
      ClientSelectionPart part = part(1, BlockPos.ZERO, rotated());

      WorkspacePreviewComposer.GeometryFrame frame = WorkspacePreviewComposer.frameForPart(part);
      Map<BlockPos, ClientBlockSnapshot> first = WorkspacePreviewComposer.resolvedForPart(part);
      Map<BlockPos, ClientBlockSnapshot> second = WorkspacePreviewComposer.resolvedForPart(part);

      assertTrue(frame != null);
      assertSame(first, second);
   }

   @Test
   void pureTranslationDoesNotBuildAFrameOrPopulateTheFrameCache() {
      assertNull(WorkspacePreviewComposer.frameForPart(part(2, BlockPos.ZERO, translated())));
      assertEquals(0, WorkspacePreviewComposer.frameCacheSizeForTesting());
   }

   @Test
   void aNewPartWithTheSameIdDoesNotUseTheOldResolvedValues() {
      ClientSelectionPart first = part(4, BlockPos.ZERO, translated());
      ClientSelectionPart replacement = part(4, new BlockPos(8, 0, 0), translated());

      WorkspacePreviewComposer.resolvedForPart(first);
      Map<BlockPos, ClientBlockSnapshot> resolved = WorkspacePreviewComposer.resolvedForPart(replacement);

      assertEquals(Map.of(new BlockPos(9, 0, 0), replacement.blocks().get(new BlockPos(8, 0, 0))), resolved);
   }

   @Test
   void frameCacheEvictsOnlyItsLeastRecentlyUsedEntryAtItsLimit() {
      int entries = WorkspacePreviewComposer.frameCacheLimitForTesting() + 1;
      for (int id = 0; id < entries; id++) {
         WorkspacePreviewComposer.frameForPart(part(id, new BlockPos(id, 0, 0), rotated()));
      }

      assertEquals(WorkspacePreviewComposer.frameCacheLimitForTesting(), WorkspacePreviewComposer.frameCacheSizeForTesting());
      assertEquals(WorkspacePreviewComposer.resolvedCacheEntryLimitForTesting(), WorkspacePreviewComposer.resolvedCacheSizeForTesting());
   }

   @Test
   void resolvedCacheEvictionLeavesTheFrameCacheIntact() {
      ClientSelectionPart framed = part(1_000, BlockPos.ZERO, rotated());
      WorkspacePreviewComposer.frameForPart(framed);
      int frameCount = WorkspacePreviewComposer.frameCacheSizeForTesting();

      for (int id = 0; id <= WorkspacePreviewComposer.resolvedCacheEntryLimitForTesting(); id++) {
         WorkspacePreviewComposer.resolvedForPart(part(id, new BlockPos(id, 0, 0), translated()));
      }

      assertEquals(frameCount, WorkspacePreviewComposer.frameCacheSizeForTesting());
      assertEquals(WorkspacePreviewComposer.resolvedCacheEntryLimitForTesting(), WorkspacePreviewComposer.resolvedCacheSizeForTesting());
      assertTrue(WorkspacePreviewComposer.resolvedCacheBlocksForTesting() <= WorkspacePreviewComposer.CLIENT_RENDER_BLOCK_LIMIT);
   }

   /**
    * An X turn still fills the frame cache. It does not rotate a block state, so this cache
    * fixture can keep a snapshot whose {@code state} is unset.
    */
   private static WorkspaceTransform rotated() {
      return new WorkspaceTransform(Vec3.ZERO, new Vec3(Math.PI * 0.5, 0.0, 0.0), OperationStackRegion.origin());
   }

   private static WorkspaceTransform translated() {
      return WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1.0, 0.0, 0.0));
   }

   private static ClientSelectionPart part(int id, BlockPos position, WorkspaceTransform transform) {
      return new ClientSelectionPart(
         id,
         ClientSelectionPart.Source.WORLD,
         null,
         Map.of(position, snapshot()),
         transform,
         false
      );
   }

   private static ClientBlockSnapshot snapshot() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (ClientBlockSnapshot)((Unsafe)field.get(null)).allocateInstance(ClientBlockSnapshot.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
