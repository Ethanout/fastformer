package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WriteFailureGameTests {
   private WriteFailureGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "write_callback_failure", timeoutTicks = 20000)
   public static void containerCallbackFailureRestoresAllTaskTypes(GameTestHelper helper) {
      ServerLevel level = helper.getLevel();
      BlockPos target = helper.absolutePos(new BlockPos(1, 2, 1));
      BlockPos source = target.above(2);
      int[] scenario = {0};
      UUID[] owner = {null};
      ReversibleBlockSnapshot[] original = {null};
      boolean[] injected = {false};
      long baseline = MemoryReservation.reservedBytes();
      Consumer<EntityJoinLevelEvent> failure = event -> {
         if (!injected[0] && event.getLevel() == level && event.getEntity() instanceof ItemEntity
            && event.getEntity().blockPosition().equals(target)) {
            injected[0] = true;
            throw new IllegalStateException("Injected container removal callback failure");
         }
      };
      helper.succeedWhen(() -> {
         if (owner[0] == null) {
            level.setBlock(target, Blocks.CHEST.defaultBlockState(), 2);
            ((ChestBlockEntity)level.getBlockEntity(target)).setItem(0, new ItemStack(Items.DIAMOND, 3));
            original[0] = ReversibleBlockSnapshot.capture(level, target).orElseThrow();
            level.setBlock(source, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
            owner[0] = UUID.randomUUID();
            injected[0] = false;
            enqueue(level, owner[0], source, target, scenario[0]);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, EntityJoinLevelEvent.class, failure);
         }
         if (scenario[0] == 0) {
            FastPlaceManager.tickWorld(level.getServer());
         } else {
            OperationManager.tickWorld(level.getServer());
         }
         helper.assertTrue(injected[0], "waiting for container callback in scenario " + scenario[0]);
         NeoForge.EVENT_BUS.unregister(failure);
         helper.assertTrue(!FastPlaceManager.taskActive(owner[0]) && !OperationManager.taskActive(owner[0]),
            "failed writer remained active");
         WorldHistoryManager.tickWorld(level.getServer());
         helper.assertTrue(!WorldHistoryManager.busy(owner[0]), "waiting for failure recovery");
         helper.assertTrue(original[0].matches(level, target), "failure did not restore chest and inventory");
         helper.assertTrue(level.getBlockState(source).is(Blocks.GOLD_BLOCK), "copy changed its source");
         helper.assertTrue(!WorldWriteCoordinator.busy(level.getServer(), level.dimension()), "failure retained write lease");
         helper.assertTrue(MemoryReservation.reservedBytes() == baseline, "failure retained memory reservation");
         owner[0] = null;
         scenario[0]++;
         helper.assertTrue(scenario[0] == 3, "running next task type");
      });
   }

   private static void enqueue(ServerLevel level, UUID owner, BlockPos source, BlockPos target, int kind) {
      if (kind == 0) {
         FastPlaceManager.addTaskForTest(owner, PlacementTask.ready(Set.of(target), new PlacementTaskPlan(
            Blocks.GOLD_BLOCK.defaultBlockState(), null, OperationConflictMode.REPLACE,
            PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension())));
      } else if (kind == 1) {
         var part = new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.CLIPBOARD,
            Map.of(target, new ClientBlockSnapshot(Blocks.GOLD_BLOCK.defaultBlockState(), null)), WorkspaceTransform.IDENTITY, false);
         OperationManager.addTaskForTest(owner, new ClientWorkspacePlacementTask(UUID.randomUUID(),
            new OperationWorkspacePlan(List.of(part)), PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension()));
      } else {
         OperationManager.addTaskForTest(owner, new SelectionOperationTask(
            OperationSelectionVolume.cuboid(source, source, source, source), OperationMode.MOVE,
            OperationConflictMode.REPLACE, true, target.subtract(source), OperationStackRegion.origin(),
            PlacementUpdateMode.CLIENT_ONLY, 16, level.dimension()));
      }
   }
}
