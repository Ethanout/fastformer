package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlacementWakeupGameTests {
   private PlacementWakeupGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "placement_wakeup", timeoutTicks = 20000)
   public static void journalWakeupServicesOrdinaryPlacement(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
      int[] sizes = {1, 16, 256};
      int[] scenario = {0};
      WakeupScenario[] active = {null};
      helper.succeedWhen(() -> {
         if (active[0] == null) {
            active[0] = new WakeupScenario(level, origin, sizes[scenario[0]]);
         }
         WakeupScenario current = active[0];
         if (!current.journalCompleted) {
            helper.assertTrue(current.task.prepare(), "waiting for placement preparation");
            helper.assertTrue(current.task.acquireLease(current.context), "waiting for placement lease");
            if (current.task.validateSnapshots(level, WorldTaskBudget.forServerTick())) {
               current.task.prepareJournal(current.context);
            }
            helper.assertTrue(current.task.placed() == 0, "placement wrote before durable journal");
            if (!current.io.isEmpty()) {
               current.journalCompleted = true;
               current.io.removeFirst().run();
            }
            helper.assertTrue(false, "waiting for ordinary placement wakeup");
         }
         helper.assertTrue(current.resumes == 1, "journal callback did not resume the manager exactly once");
         helper.assertTrue(current.placedInResume > 0, "manager wakeup did not write any block");
         if (!current.stopping) {
            FastPlaceManager.cancelTask(current.context);
            current.stopping = true;
         }
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(current.context.owner()), "waiting for wakeup test cleanup");
         current.positions.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2));
         active[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == sizes.length, "running next ordinary placement size");
      });
   }

   private static final class WakeupScenario {
      final PlacementTask task;
      final WorldTaskContext context;
      final Set<BlockPos> positions = new LinkedHashSet<>();
      final ArrayDeque<Runnable> io = new ArrayDeque<>();
      boolean journalCompleted;
      boolean stopping;
      int resumes;
      int placedInResume;

      WakeupScenario(ServerLevel level, BlockPos origin, int count) {
         for (int index = 0; index < count; index++) {
            BlockPos pos = origin.offset(index % 16, 0, index / 16);
            positions.add(pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
         }
         task = PlacementTask.ready(positions, new PlacementTaskPlan(Blocks.GOLD_BLOCK.defaultBlockState(),
            null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension()));
         try {
            var field = PlacementTask.class.getDeclaredField("journalPreparation");
            field.setAccessible(true);
            field.set(task, new WorldJournalPreparation(io::addLast));
         } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not control ordinary placement journal", exception);
         }
         WorldTaskContext owner = new WorldTaskContext(level.getServer(), UUID.randomUUID());
         context = owner.withResume(() -> {
            resumes++;
            // Register only when the callback runs, so Post cannot service this fixture first.
            FastPlaceManager.addTaskForTest(owner.owner(), task);
            try {
               var resume = FastPlaceManager.class.getDeclaredMethod("resumeTask", WorldTaskContext.class, PlacementTask.class);
               resume.setAccessible(true);
               resume.invoke(null, owner, task);
            } catch (ReflectiveOperationException exception) {
               throw new IllegalStateException("Could not resume ordinary placement", exception);
            }
            placedInResume = task.placed();
         });
      }
   }
}
