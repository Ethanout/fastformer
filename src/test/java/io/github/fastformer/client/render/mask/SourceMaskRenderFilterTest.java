package io.github.fastformer.client.render.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.render.mask.SourceMaskRenderFilter.Snapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceMaskRenderFilterTest {
   private static final BlockPos FIRST = new BlockPos(1, 2, 3);
   private static final BlockPos SECOND = new BlockPos(-4, 70, 12);

   private final SourceMaskRenderFilter filter = SourceMaskRenderFilter.instance();

   @BeforeEach
   void startEmpty() {
      this.filter.clear();
   }

   @AfterEach
   void leaveEmpty() {
      this.filter.clear();
   }

   @Test
   void publishingPositionsHidesThemAndAdvancesTheRevision() {
      long before = this.filter.revision();

      assertTrue(this.filter.publish(List.of(FIRST, SECOND)));

      assertEquals(before + 1L, this.filter.revision());
      assertTrue(this.filter.hides(FIRST));
      assertTrue(this.filter.hides(FIRST.asLong()));
      assertFalse(this.filter.hides(BlockPos.ZERO));
      assertFalse(this.filter.hides((BlockPos)null));
      assertEquals(Set.of(FIRST, SECOND), this.filter.positions());
   }

   @Test
   void anUnchangedSetKeepsTheRevisionAndTheSnapshot() {
      this.filter.publish(List.of(FIRST, SECOND));
      Snapshot first = this.filter.snapshot();

      assertFalse(this.filter.publish(List.of(SECOND, FIRST)));
      assertFalse(this.filter.publish(List.of(FIRST.immutable(), SECOND.immutable())));

      assertEquals(first.revision(), this.filter.revision());
      assertSame(first, this.filter.snapshot());
   }

   @Test
   void emptyAndNullInputsPublishTheEmptyMask() {
      this.filter.publish(List.of(FIRST));

      assertTrue(this.filter.publish(null));

      assertTrue(this.filter.isEmpty());
      assertTrue(this.filter.positions().isEmpty());
      assertFalse(this.filter.hides(FIRST));
   }

   @Test
   void duplicateAndNullPositionsCollapseToOneEntry() {
      assertTrue(this.filter.publish(Arrays.asList(FIRST, FIRST, null, FIRST.immutable())));

      assertEquals(1, this.filter.positions().size());
      assertEquals(Set.of(FIRST), this.filter.positions());
   }

   @Test
   void clearHidesNothingAndKeepsTheRevisionMonotonic() {
      this.filter.publish(List.of(FIRST));
      long masked = this.filter.revision();

      assertTrue(this.filter.clear());

      assertEquals(masked + 1L, this.filter.revision());
      assertFalse(this.filter.hides(FIRST));
      assertTrue(this.filter.isEmpty());
   }

   @Test
   void anEarlierSnapshotKeepsItsOwnPositionsAfterAPublication() {
      this.filter.publish(List.of(FIRST));
      Snapshot bound = this.filter.snapshot();

      this.filter.publish(List.of(SECOND));

      // One mesh build binds one snapshot. A publication during the build must not change
      // the blocks that the running build already reads.
      assertTrue(bound.hides(FIRST.asLong()));
      assertFalse(bound.hides(SECOND.asLong()));
      assertNotSame(bound, this.filter.snapshot());
      assertTrue(this.filter.snapshot().hides(SECOND.asLong()));
      assertFalse(this.filter.snapshot().hides(FIRST.asLong()));
   }
}
