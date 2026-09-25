package io.github.fastformer.client.operation.selection;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import net.minecraft.core.BlockPos;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionBlockCaptureTest {
   private static final class World implements SelectionBlockCapture.SnapshotSource {
      int reads;
      boolean loaded = true;
      final Thread owner = Thread.currentThread();
      public Object identity() { return this; }
      public boolean loaded(BlockPos position) { return loaded; }
      public ClientBlockSnapshot read(BlockPos position) { assertSame(owner, Thread.currentThread()); reads++; return null; }
   }
   private static OperationSelectionVolume selection(int maxX) {
      return OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(maxX, 9, 9), null, null);
   }

   @Test void cancellationAndReplacementNeverPublishOldResults() {
      var world = new World();
      var capture = new SelectionBlockCapture(() -> world);
      capture.start(selection(199), null, () -> true, blocks -> fail("cancelled capture published"), key -> fail(key));
      capture.tick();
      assertTrue(capture.pending());
      assertTrue(world.reads <= 4096);
      capture.cancel();
      int reads = world.reads;
      capture.tick();
      assertEquals(reads, world.reads);
      var completed = new AtomicBoolean();
      capture.start(selection(0), null, () -> true, blocks -> completed.set(true), key -> fail(key));
      while (capture.pending()) capture.tick();
      assertTrue(completed.get());
   }

   @Test void unloadedChunkFailsWithoutReadingOrPublishingPartialWorld() {
      var world = new World(); world.loaded = false;
      var failure = new AtomicReference<String>();
      var capture = new SelectionBlockCapture(() -> world);
      capture.start(selection(0), null, () -> true, blocks -> fail("unloaded capture published"), failure::set);
      capture.tick();
      assertEquals(0, world.reads);
      assertFalse(capture.pending());
      assertEquals("fastformer.message.operation_capture_failed", failure.get());
   }

   @Test void lostOwnerOrChangedWorldStopsBeforeAnyFurtherRead() {
      var world = new AtomicReference<>(new World());
      var owned = new AtomicBoolean(true);
      var capture = new SelectionBlockCapture(world::get);
      capture.start(selection(199), null, owned::get, blocks -> fail("stale capture published"), key -> fail(key));
      owned.set(false); capture.tick();
      assertEquals(0, world.get().reads);
      owned.set(true);
      capture.start(selection(199), null, owned::get, blocks -> fail("old world published"), key -> fail(key));
      world.set(new World()); capture.tick();
      assertEquals(0, world.get().reads);
      assertFalse(capture.pending());
   }
}
