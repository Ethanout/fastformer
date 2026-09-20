package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.render.model.GhostMesh;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GhostMeshCacheTest {
   @Test
   void sameInputRetriesAfterFailureAndRejectsNullResults() {
      for (boolean nullResult : new boolean[] {false, true}) {
         AtomicInteger attempts = new AtomicInteger();
         GhostMesh expected = GhostMesh.empty();
         GhostMeshCache cache = new GhostMeshCache(blocks -> {
            if (attempts.incrementAndGet() == 1) {
               if (nullResult) return null;
               throw new IllegalStateException("transient mesh failure");
            }
            return expected;
         });
         Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
         assertThrows(RuntimeException.class, () -> cache.mesh(blocks));
         assertSame(expected, cache.mesh(blocks));
         assertEquals(2, attempts.get());
      }
   }

   @Test
   void failedReplacementDoesNotAssociateTheOldMeshWithNewBlocks() {
      AtomicInteger attempts = new AtomicInteger();
      GhostMesh oldMesh = GhostMesh.empty();
      GhostMesh replacement = GhostMesh.empty();
      Set<BlockPos> original = Set.of(BlockPos.ZERO);
      Set<BlockPos> changed = Set.of(new BlockPos(1, 0, 0));
      GhostMeshCache cache = new GhostMeshCache(blocks -> {
         int attempt = attempts.incrementAndGet();
         if (attempt == 2) {
            throw new IllegalStateException("transient mesh failure");
         }
         return blocks.equals(original) ? oldMesh : replacement;
      });

      assertSame(oldMesh, cache.mesh(original));
      assertThrows(IllegalStateException.class, () -> cache.mesh(changed));
      assertSame(oldMesh, cache.mesh(original));
      assertEquals(2, attempts.get());
      assertSame(replacement, cache.mesh(changed));
      assertEquals(3, attempts.get());
      assertSame(replacement, cache.mesh(changed));
      assertEquals(3, attempts.get());
   }

   @Test
   void successfulEmptyMeshIsCachedRatherThanRetried() {
      AtomicInteger attempts = new AtomicInteger();
      GhostMeshCache cache = new GhostMeshCache(blocks -> {
         attempts.incrementAndGet();
         return GhostMesh.empty();
      });
      Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
      cache.mesh(blocks);
      cache.mesh(blocks);
      assertEquals(1, attempts.get());
      cache.clear();
      cache.mesh(blocks);
      assertEquals(2, attempts.get());
   }
}
