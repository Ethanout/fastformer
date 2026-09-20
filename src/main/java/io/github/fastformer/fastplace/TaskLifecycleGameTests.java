package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationStackRegion;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskLifecycleGameTests {
   private static final int[] SIZES = {1, 16, 256};

   private TaskLifecycleGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "dimension_availability", timeoutTicks = 20000)
   public static void partialTasksWaitForDimensionAndResume(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
      int[] scenario = {0};
      Object[] active = {null};
      WorldTaskContext[] context = {null};
      boolean[] waitingChecked = {false};
      helper.succeedWhen(() -> {
         if (active[0] == null) {
            positions(origin, 16).forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2));
            context[0] = new WorldTaskContext(level.getServer(), UUID.randomUUID());
            if (scenario[0] == 0) {
               active[0] = placementTask(level, positions(origin, 16));
            } else if (scenario[0] == 1) {
               active[0] = workspaceTask(level, positions(origin, 16));
            } else {
               BlockPos source = origin.above(2);
               positions(source, 16).forEach(pos -> level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2));
               active[0] = new SelectionOperationTask(
                  OperationSelectionVolume.cuboid(source, source.offset(15, 0, 0), source, source.offset(15, 0, 0)),
                  OperationMode.MOVE, OperationConflictMode.REPLACE, true, new BlockPos(0, -2, 0),
                  OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension());
            }
         }
         UUID owner = context[0].owner();
         if (!waitingChecked[0]) {
            if (active[0] instanceof PlacementTask placement) {
               advancePlacementToFirstWrite(helper, placement, context[0], level);
               helper.assertTrue(placement.hasWrites(), "waiting for placement first write");
               FastPlaceManager.addTaskForTest(owner, placement);
            } else {
               WorldOperationTask operation = (WorldOperationTask)active[0];
               helper.assertTrue(operation.acquireLease(context[0]), "waiting for operation lease");
               helper.assertTrue(operation.tick(context[0], level, oneCellBudget()) == OperationTaskResult.ACTIVE,
                  "operation ended before dimension became unavailable");
               helper.assertTrue(operation.hasWrites(), "waiting for operation first write");
               OperationManager.addTaskForTest(owner, operation);
            }
            assertPartialWrite(helper, level, origin, 16);
            serviceUnavailableDimension(active[0], owner);
            helper.assertTrue(FastPlaceManager.taskActive(owner) || OperationManager.taskActive(owner),
               "unavailable dimension discarded writer");
            helper.assertTrue(!WorldHistoryManager.busy(owner), "unavailable dimension unexpectedly started recovery");
            assertPartialWrite(helper, level, origin, 16);
            helper.assertTrue(taskField(active[0], "memoryReservation") == null,
               "unavailable dimension retained active working reservation");
            helper.assertTrue(taskField(active[0], "journalPreparation") != null,
               "unavailable dimension discarded journal ownership");
            waitingChecked[0] = true;
         }
         if (active[0] instanceof PlacementTask) {
            FastPlaceManager.tickWorld(level.getServer());
         } else {
            OperationManager.tickWorld(level.getServer());
         }
         helper.assertTrue(!FastPlaceManager.taskActive(owner) && !OperationManager.taskActive(owner),
            "waiting for resumed writer");
         helper.assertTrue(!WorldHistoryManager.busy(owner), "resumed writer failed into recovery");
         assertBlockRange(helper, level, origin, 16, Blocks.GOLD_BLOCK);
         helper.assertTrue(!io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
            "resumed writer retained lease");
         active[0] = null;
         waitingChecked[0] = false;
         scenario[0]++;
         helper.assertTrue(scenario[0] == 3, "running next dimension availability scenario");
      });
   }

   private static void serviceUnavailableDimension(Object task, UUID owner) {
      try {
         Class<?> manager = task instanceof PlacementTask ? FastPlaceManager.class : OperationManager.class;
         var method = manager.getDeclaredMethod("tickTask", WorldTaskContext.class);
         method.setAccessible(true);
         method.invoke(null, new WorldTaskContext(null, owner));
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Could not service unavailable dimension", exception);
      }
   }

   private static Object taskField(Object task, String name) {
      try {
         var field = task.getClass().getDeclaredField(name);
         field.setAccessible(true);
         return field.get(task);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Could not inspect task ownership", exception);
      }
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void unchangedPlacementCompletesWithoutRecovery(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(1, 97, 1));
      level.setBlock(position, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      UUID owner = UUID.randomUUID();
      PlacementTask task = placementTask(level, Set.of(position));
      FastPlaceManager.addTaskForTest(owner, task);
      helper.succeedWhen(() -> {
         FastPlaceManager.tickWorld(level.getServer());
         helper.assertTrue(!FastPlaceManager.taskActive(owner), "waiting for unchanged placement");
         helper.assertTrue(!WorldHistoryManager.busy(owner), "unchanged placement created recovery");
         helper.assertTrue(task.metricsSummary().contains("phase=COMPLETE"), "unchanged placement did not complete normally");
         helper.assertTrue(level.getBlockState(position).is(Blocks.GOLD_BLOCK), "unchanged placement changed its target");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void clientOnlyWorkspaceKeepsFloatingGrass(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos support = helper.absolutePos(new BlockPos(1, 2, 1));
      BlockPos grass = support.above();
      UUID owner = UUID.randomUUID();
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      // Insert in the unsafe order to verify task-wide support-first ordering.
      blocks.put(grass, new ClientBlockSnapshot(Blocks.SHORT_GRASS.defaultBlockState(), null));
      blocks.put(support, new ClientBlockSnapshot(Blocks.DIRT.defaultBlockState(), null));
      OperationWorkspacePlan.Part part = new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.CLIPBOARD, blocks, WorkspaceTransform.IDENTITY, false
      );
      ClientWorkspacePlacementTask task = new ClientWorkspacePlacementTask(
         UUID.randomUUID(),
         new OperationWorkspacePlan(List.of(part)),
         PlacementUpdateMode.CLIENT_ONLY,
         16,
         level.dimension()
      );
      OperationManager.addTaskForTest(owner, task);

      helper.succeedWhen(() -> {
         OperationManager.tickWorld(level.getServer());
         helper.assertTrue(!OperationManager.taskActive(owner), "waiting for client-only workspace placement");
         helper.assertTrue(!WorldHistoryManager.busy(owner), "floating grass workspace entered recovery");
         helper.assertTrue(level.getBlockState(support).is(Blocks.DIRT), "workspace did not place grass support");
         helper.assertTrue(level.getBlockState(grass).is(Blocks.SHORT_GRASS), "client-only workspace removed floating grass");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void placementCompletesAtRepresentativeSizes(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 33, 1));
      int[] scenario = {0};
      UUID[] owner = {null};
      PlacementTask[] active = {null};
      helper.succeedWhen(() -> {
         int count = SIZES[scenario[0]];
         if (owner[0] == null) {
            clearRange(level, origin, count);
            owner[0] = UUID.randomUUID();
            active[0] = placementTask(level, positions(origin, count));
            FastPlaceManager.addTaskForTest(owner[0], active[0]);
         }
         FastPlaceManager.tickWorld(level.getServer());
         helper.assertTrue(!FastPlaceManager.taskActive(owner[0]), "waiting for placement size " + count + ": " + active[0].metricsSummary());
         assertBlockRange(helper, level, origin, count, Blocks.GOLD_BLOCK);
         clearRange(level, origin, count);
         owner[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == SIZES.length, "running next placement size");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void workspaceCompletesAtRepresentativeSizes(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 49, 1));
      int[] scenario = {0};
      UUID[] owner = {null};
      ClientWorkspacePlacementTask[] active = {null};
      helper.succeedWhen(() -> {
         int count = SIZES[scenario[0]];
         if (owner[0] == null) {
            clearRange(level, origin, count);
            owner[0] = UUID.randomUUID();
            active[0] = workspaceTask(level, positions(origin, count));
            OperationManager.addTaskForTest(owner[0], active[0]);
         }
         OperationManager.tickWorld(level.getServer());
         if (OperationManager.taskActive(owner[0])) {
            // GameTestServer skips the normal tick delay. Give asynchronous disk
            // commits real time to run before the test spends its tick budget.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
         }
         helper.assertTrue(!OperationManager.taskActive(owner[0]), "waiting for workspace size " + count + ": " + active[0].metricsSummary());
         assertBlockRange(helper, level, origin, count, Blocks.GOLD_BLOCK);
         clearRange(level, origin, count);
         owner[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == SIZES.length, "running next workspace size");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void cancelledPlacementRestoresPartialWrite(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 65, 1));
      Set<BlockPos> positions = positions(origin, 16);
      positions.forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2));
      PlacementTask task = placementTask(level, positions);
      WorldTaskContext context = new WorldTaskContext(level.getServer(), UUID.randomUUID());
      boolean[] cancelled = {false};
      helper.succeedWhen(() -> {
         if (!cancelled[0]) {
            advancePlacementToFirstWrite(helper, task, context, level);
            helper.assertTrue(task.hasWrites(), "waiting for first placement write");
            assertPartialWrite(helper, level, origin, 16);
            helper.assertTrue(WorldHistoryManager.acceptStoppedTask(context, task).recoveryCreated(),
               "partial placement did not create recovery");
            cancelled[0] = true;
         }
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(context.owner()), "waiting for placement recovery");
         assertBlockRange(helper, level, origin, 16, Blocks.STONE);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "task_lifecycle", timeoutTicks = 20000)
   public static void cancelledWorkspaceRestoresPartialWrite(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos origin = helper.absolutePos(new BlockPos(1, 81, 1));
      Set<BlockPos> positions = positions(origin, 16);
      positions.forEach(pos -> level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2));
      ClientWorkspacePlacementTask task = workspaceTask(level, positions);
      WorldTaskContext context = new WorldTaskContext(level.getServer(), UUID.randomUUID());
      boolean[] cancelled = {false};
      helper.succeedWhen(() -> {
         if (!cancelled[0]) {
            helper.assertTrue(task.acquireLease(context), "waiting for workspace lease");
            OperationTaskResult result = task.tick(context, level, oneCellBudget());
            helper.assertTrue(result == OperationTaskResult.ACTIVE, "workspace ended before cancellation: " + result);
            helper.assertTrue(task.hasWrites(), "waiting for first workspace write");
            assertPartialWrite(helper, level, origin, 16);
            helper.assertTrue(WorldHistoryManager.acceptStoppedTask(context, task).recoveryCreated(),
               "partial workspace did not create recovery");
            cancelled[0] = true;
         }
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(context.owner()), "waiting for workspace recovery");
         assertBlockRange(helper, level, origin, 16, Blocks.STONE);
      });
   }

   private static void advancePlacementToFirstWrite(
      GameTestHelper helper, PlacementTask task, WorldTaskContext context, ServerLevel level
   ) {
      helper.assertTrue(task.prepare(), "placement generation is not ready");
      helper.assertTrue(task.ensureMemoryReservation(), "placement reservation unavailable");
      helper.assertTrue(task.acquireLease(context), "placement lease unavailable");
      if (!task.snapshotsComplete()) {
         task.validateSnapshots(level, oneCellBudget());
         return;
      }
      JournalPreparation journal = task.prepareJournal(context);
      if (journal != JournalPreparation.READY || !task.blocks().hasNext()) {
         return;
      }
      BlockPos position = task.blocks().next();
      task.consumed();
      task.place(context, level, position);
   }

   private static PlacementTask placementTask(ServerLevel level, Set<BlockPos> positions) {
      return PlacementTask.ready(positions, new PlacementTaskPlan(
         Blocks.GOLD_BLOCK.defaultBlockState(), null, OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension()
      ));
   }

   private static ClientWorkspacePlacementTask workspaceTask(ServerLevel level, Set<BlockPos> positions) {
      Map<BlockPos, ClientBlockSnapshot> blocks = new LinkedHashMap<>();
      positions.forEach(pos -> blocks.put(
         pos, new ClientBlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState(), null)
      ));
      OperationWorkspacePlan.Part part = new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.CLIPBOARD, blocks, WorkspaceTransform.IDENTITY, false
      );
      return new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of(part)),
         PlacementUpdateMode.CLIENT_ONLY, 1024, level.dimension()
      );
   }

   private static Set<BlockPos> positions(BlockPos origin, int count) {
      LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
      for (int index = 0; index < count; index++) {
         positions.add(origin.offset(index % 16, 0, index / 16));
      }
      return Set.copyOf(positions);
   }

   private static WorldTaskBudget oneCellBudget() {
      try {
         Constructor<WorldTaskBudget> constructor = WorldTaskBudget.class.getDeclaredConstructor(
            int.class, int.class, long.class, LongSupplier.class
         );
         constructor.setAccessible(true);
         return constructor.newInstance(1, 1, 0L, (LongSupplier)() -> 0L);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Could not create one-cell GameTest budget", exception);
      }
   }

   private static void assertBlockRange(
      GameTestHelper helper, ServerLevel level, BlockPos origin, int count, net.minecraft.world.level.block.Block block
   ) {
      for (int index = 0; index < count; index++) {
         helper.assertTrue(level.getBlockState(origin.offset(index % 16, 0, index / 16)).is(block),
            "unexpected block at index " + index);
      }
   }

   private static void assertPartialWrite(GameTestHelper helper, ServerLevel level, BlockPos origin, int count) {
      int gold = 0;
      int stone = 0;
      for (int index = 0; index < count; index++) {
         if (level.getBlockState(origin.offset(index % 16, 0, index / 16)).is(Blocks.GOLD_BLOCK)) {
            gold++;
         } else if (level.getBlockState(origin.offset(index % 16, 0, index / 16)).is(Blocks.STONE)) {
            stone++;
         }
      }
      helper.assertTrue(gold == 1 && stone == count - 1,
         "expected one partial write, found " + gold + " writes and " + stone + " original blocks");
   }

   private static void clearRange(ServerLevel level, BlockPos origin, int count) {
      for (int index = 0; index < count; index++) {
         level.setBlock(origin.offset(index % 16, 0, index / 16), Blocks.AIR.defaultBlockState(), 2);
      }
   }

}
