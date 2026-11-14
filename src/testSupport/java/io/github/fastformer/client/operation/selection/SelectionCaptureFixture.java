package io.github.fastformer.client.operation.selection;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Uses the real scan cursor and cancellation checks with a controlled empty world. */
public final class SelectionCaptureFixture {
   public final List<BlockPos> reads = new ArrayList<>();
   public boolean loaded = true;
   private SelectionBlockCapture.SnapshotSource world = world();
   public final SelectionBlockCapture capture = new SelectionBlockCapture(() -> world);

   public void changeWorld() { world = world(); }

   private SelectionBlockCapture.SnapshotSource world() {
      return new SelectionBlockCapture.SnapshotSource() {
         public Object identity() { return this; }
         public boolean loaded(BlockPos position) { return loaded; }
         public ClientBlockSnapshot read(BlockPos position) { reads.add(position); return null; }
      };
   }
}
