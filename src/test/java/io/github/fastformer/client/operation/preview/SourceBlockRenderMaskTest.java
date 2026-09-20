package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.render.mask.SourceMaskRenderFilter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class SourceBlockRenderMaskTest {
   private static final BlockPos SOURCE = new BlockPos(4, 5, 6);

   @Test
   void denseSourceMaskKeepsItsSnapshotAndRevisionWhenRepublished() {
      Map<BlockPos, ClientBlockSnapshot> blocks = new java.util.LinkedHashMap<>();
      ClientBlockSnapshot value = snapshot();
      for (int x = 0; x < 100; x++) {
         for (int y = 0; y < 100; y++) {
            for (int z = 0; z < 11; z++) {
               blocks.put(new BlockPos(x, y, z), value);
            }
         }
      }
      ClientSelectionPart part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, null, blocks,
         WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1, 0, 0)), false
      );
      Set<BlockPos> positions = SourceBlockRenderMask.maskedSourcePositions(List.of(part));
      assertEquals(110_000, positions.size());
      assertThrows(UnsupportedOperationException.class, positions::clear);
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(positions);
      var frozen = SourceMaskRenderFilter.instance().snapshot();
      mask.replace(positions);
      assertEquals(frozen.revision(), SourceMaskRenderFilter.instance().revision());
      assertTrue(mask.contains(new BlockPos(99, 99, 10)));
      mask.clear();
      assertFalse(mask.contains(BlockPos.ZERO));
      assertTrue(frozen.hides(BlockPos.ZERO.asLong()));
      assertThrows(UnsupportedOperationException.class, () -> frozen.positions().clear());
   }

   @AfterEach
   void leaveNoMaskBehind() {
      // The mask is process wide, because the renderer reads it without a mask reference.
      new SourceBlockRenderMask().clear();
   }

   @Test
   void maskedSourcePositionsFollowThePartSourceAndItsDisplacement() {
      WorkspaceTransform moved = WorkspaceTransform.IDENTITY.withTranslation(new Vec3(2.0, 0.0, 0.0));

      ClientSelectionPart movedWorldPart = part(1, ClientSelectionPart.Source.WORLD, moved, false);
      ClientSelectionPart deletingWorldPart = part(2, ClientSelectionPart.Source.WORLD, WorkspaceTransform.IDENTITY, true);
      ClientSelectionPart stationaryWorldPart = part(3, ClientSelectionPart.Source.WORLD, WorkspaceTransform.IDENTITY, false);
      ClientSelectionPart movedClipboardPart = part(4, ClientSelectionPart.Source.CLIPBOARD, moved, false);

      assertEquals(Set.of(SOURCE), SourceBlockRenderMask.maskedSourcePositions(List.of(movedWorldPart)));
      assertEquals(Set.of(SOURCE), SourceBlockRenderMask.maskedSourcePositions(List.of(deletingWorldPart)));
      assertEquals(Set.of(), SourceBlockRenderMask.maskedSourcePositions(List.of(stationaryWorldPart)));
      assertEquals(Set.of(), SourceBlockRenderMask.maskedSourcePositions(List.of(movedClipboardPart)));
      assertEquals(
         Set.of(SOURCE),
         SourceBlockRenderMask.maskedSourcePositions(List.of(stationaryWorldPart, deletingWorldPart))
      );
      assertEquals(Set.of(), SourceBlockRenderMask.maskedSourcePositions(null));
   }

   @Test
   void theMaskHidesAPositionWithoutAnyClientWorld() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(Set.of(SOURCE, new BlockPos(5, 5, 6)));

      // No client level exists in this test. A mask that still wrote block state could not
      // pass this test, so the result is the evidence that the mask is render only.
      assertTrue(mask.contains(SOURCE));
      assertTrue(mask.shouldSkipWorldRender(SOURCE));
      assertFalse(mask.shouldSkipWorldRender(null));
      assertFalse(mask.contains(null));
      assertEquals(Set.of(SOURCE, new BlockPos(5, 5, 6)), mask.positions());
   }

   @Test
   void clearingTheMaskDropsEveryPosition() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(Set.of(SOURCE));

      mask.clear();

      assertTrue(mask.positions().isEmpty());
      assertFalse(mask.contains(SOURCE));
   }

   @Test
   void committingAMoveEndsTheMaskWithoutLocalPredictions() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(Set.of(SOURCE));

      // The committed state belongs to the server. The mask must not write the target
      // position, and it must stop hiding the old source position.
      mask.complete(Map.of(SOURCE, snapshot()));

      assertTrue(mask.positions().isEmpty());
      assertFalse(mask.contains(SOURCE));
   }

   @Test
   void discardingTheMaskRestoresVisibility() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(Set.of(SOURCE));

      mask.discard();

      assertFalse(mask.contains(SOURCE));
      assertTrue(mask.positions().isEmpty());
   }

   @Test
   void repeatingTheSameMaskKeepsOneSnapshot() {
      SourceBlockRenderMask mask = new SourceBlockRenderMask();
      mask.replace(Set.of(SOURCE));
      long revision = SourceMaskRenderFilter.instance().revision();

      mask.replace(List.of(SOURCE.immutable(), SOURCE.immutable()));

      assertEquals(revision, SourceMaskRenderFilter.instance().revision());
   }

   private static ClientSelectionPart part(
      int id, ClientSelectionPart.Source source, WorkspaceTransform transform, boolean pendingDelete
   ) {
      return new ClientSelectionPart(
         id,
         source,
         null,
         Map.of(SOURCE, snapshot()),
         transform,
         pendingDelete
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
