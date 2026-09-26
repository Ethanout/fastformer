package io.github.fastformer.network;

import io.github.fastformer.FastFormer;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.network.codec.OperationWorkspacePlanCodec;
import io.github.fastformer.network.transfer.WorkspaceDecodeQueue;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UploadBudgetGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "upload_decode_budget", timeoutTicks = 100000)
   public static void concurrentLargeDecodesKeepServerResponsive(GameTestHelper helper) throws Exception {
      int cells = 65_536;
      var blocks = new LinkedHashMap<BlockPos, ClientBlockSnapshot>();
      var stone = new ClientBlockSnapshot(Blocks.STONE.defaultBlockState(), null);
      for (int i = 0; i < cells; i++) blocks.put(new BlockPos(i % 256, 64, i / 256), stone);
      var plan = new OperationWorkspacePlan(List.of(new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.WORLD,
         blocks, WorkspaceTransform.IDENTITY, false)));
      byte[] bytes = OperationWorkspacePlanCodec.encodeCompressed(plan);
      var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
      var queue = new WorkspaceDecodeQueue();
      int[] done = {0}, ticks = {0};
      Exception[] failure = {null};
      long[] peakHeap = {0}, peakTickWork = {0};
      Thread serverThread = Thread.currentThread();
      for (int i = 0; i < 3; i++) {
         helper.assertTrue(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> {
            if (Thread.currentThread() == serverThread) throw new IllegalStateException("decode ran on server thread");
            return OperationWorkspacePlanCodec.decodeCompressed(bytes, registry);
         }, helper.getLevel().getServer(), (decoded, error) -> {
            if (error != null) failure[0] = error;
            else if (decoded.parts().getFirst().blocks().size() != cells) failure[0] = new IllegalStateException("decoded plan changed");
            done[0]++;
         }), "valid decode was refused below capacity");
      }
      helper.assertFalse(queue.submit(UUID.randomUUID(), UUID.randomUUID(), () -> null, helper.getLevel().getServer(), (p, e) -> {}), "fourth decode exceeded capacity");
      helper.succeedWhen(() -> {
         long started = System.nanoTime();
         ticks[0]++;
         peakHeap[0] = Math.max(peakHeap[0], java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
         peakTickWork[0] = Math.max(peakTickWork[0], System.nanoTime() - started);
         helper.assertTrue(done[0] == 3, "decode results are pending");
         helper.assertTrue(failure[0] == null, "decode failed: " + failure[0]);
         helper.assertTrue(ticks[0] > 1, "no server tick ran while the decodes were pending");
         org.slf4j.LoggerFactory.getLogger(UploadBudgetGameTests.class).info(
            "UPLOAD_BUDGET cellsPerPlan={} compressedBytes={} plans=3 responsiveTicks={} peakProbeNanos={} peakJvmHeapBytes={} averageServerTickMillis={}",
            cells, bytes.length, ticks[0], peakTickWork[0], peakHeap[0], helper.getLevel().getServer().getAverageTickTimeNanos() / 1_000_000.0);
      });
   }
}
