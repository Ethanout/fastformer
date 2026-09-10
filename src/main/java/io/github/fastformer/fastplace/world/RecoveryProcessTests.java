package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Development-only two-process recovery harness. Excluded from the production jar. */
@EventBusSubscriber(modid = FastFormer.MOD_ID)
public final class RecoveryProcessTests {
   private static final String PROPERTY = "fastformer.recoveryProcessTest";
   private static final Path CRASH_MARKER = Path.of("recovery-process-crash.txt");
   private static final Path RESULT = Path.of("recovery-process-result.txt");
   private static final BlockPos PLACEMENT_POS = new BlockPos(0, 100, 0);
   private static final BlockPos WORKSPACE_POS = new BlockPos(0, 80, 0);
   private static final BlockPos SELECTION_SOURCE = new BlockPos(0, 80, 0);
   private static final BlockPos SELECTION_TARGET = SELECTION_SOURCE.above();

   private static Mode mode = Mode.DISABLED;
   private static CrashOperations crashOperations;
   private static int crashTicks;
   private static int verifyTicks;

   private RecoveryProcessTests() {
   }

   @SubscribeEvent
   public static void onServerStarted(ServerStartedEvent event) {
      String configured = System.getProperty(PROPERTY, "");
      mode = switch (configured) {
         case "crash" -> Mode.CRASH;
         case "verify" -> Mode.VERIFY;
         default -> Mode.DISABLED;
      };
      if (mode == Mode.DISABLED) {
         return;
      }
      try {
         if (mode == Mode.CRASH) {
            Files.deleteIfExists(CRASH_MARKER);
            Files.deleteIfExists(RESULT);
            crashTicks = 0;
            crashOperations = prepareCrashOperations(event.getServer());
         } else {
            verifyTicks = 0;
         }
      } catch (Throwable failure) {
         finish(event.getServer(), "FAIL setup: " + failure);
      }
   }

   @SubscribeEvent
   public static void onServerTick(ServerTickEvent.Post event) {
      if (mode == Mode.DISABLED) {
         return;
      }
      MinecraftServer server = event.getServer();
      try {
         if (mode == Mode.CRASH) {
            if (++crashTicks > 1200) {
               throw new IllegalStateException("crash phase did not reach first-write boundary within 1200 ticks");
            }
            advanceCrash(server);
         } else if (++verifyTicks >= 5) {
            verifyRecovery(server);
         }
      } catch (Throwable failure) {
         finish(server, "FAIL runtime: " + failure);
      }
   }

   private static CrashOperations prepareCrashOperations(MinecraftServer server) {
      ServerLevel overworld = requiredLevel(server, Level.OVERWORLD);
      ServerLevel nether = requiredLevel(server, Level.NETHER);
      ServerLevel end = requiredLevel(server, Level.END);
      setBaseline(overworld, PLACEMENT_POS, Blocks.STONE.defaultBlockState());
      setBaseline(nether, WORKSPACE_POS, Blocks.STONE.defaultBlockState());
      setBaseline(end, SELECTION_SOURCE, Blocks.GOLD_BLOCK.defaultBlockState());
      setBaseline(end, SELECTION_TARGET, Blocks.STONE.defaultBlockState());
      if (!server.saveAllChunks(true, true, true)) {
         throw new IllegalStateException("baseline world save failed");
      }
      IOUtilities.waitUntilIOWorkerComplete();

      WorldTaskContext placementContext = new WorldTaskContext(server, UUID.randomUUID());
      PlacementTask placement = PlacementTask.ready(Set.of(PLACEMENT_POS), new PlacementTaskPlan(
         Blocks.GOLD_BLOCK.defaultBlockState(), null, OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 16, Level.OVERWORLD
      ));

      WorldTaskContext workspaceContext = new WorldTaskContext(server, UUID.randomUUID());
      Map<BlockPos, ClientBlockSnapshot> desired = new LinkedHashMap<>();
      desired.put(WORKSPACE_POS, new ClientBlockSnapshot(Blocks.DIAMOND_BLOCK.defaultBlockState(), null));
      var workspacePart = new OperationWorkspacePlan.Part(
         1, ClientSelectionPart.Source.CLIPBOARD, desired, WorkspaceTransform.IDENTITY, false
      );
      var workspace = new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of(workspacePart)),
         PlacementUpdateMode.CLIENT_ONLY, 16, Level.NETHER
      );

      WorldTaskContext selectionContext = new WorldTaskContext(server, UUID.randomUUID());
      var selectionVolume = OperationSelectionVolume.cuboid(
         SELECTION_SOURCE, SELECTION_SOURCE, SELECTION_SOURCE, SELECTION_SOURCE
      );
      var selection = new SelectionOperationTask(
         selectionVolume, OperationMode.MOVE, OperationConflictMode.REPLACE, true,
         BlockPos.ZERO.above(), OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY,
         16, Level.END
      );
      return new CrashOperations(
         placement, placementContext, workspace, workspaceContext, selection, selectionContext
      );
   }

   private static void advanceCrash(MinecraftServer server) throws IOException {
      CrashOperations operations = crashOperations;
      if (operations == null) {
         throw new IllegalStateException("crash operations were not initialized");
      }
      advancePlacement(operations, requiredLevel(server, Level.OVERWORLD));
      advanceWorkspace(operations, requiredLevel(server, Level.NETHER));
      advanceSelection(operations, requiredLevel(server, Level.END));
      if (!operations.allWritten()) {
         return;
      }
      if (!PersistentRecoveryJournal.awaitIoIdle()
         || operations.placement().journal() == null
         || operations.workspace().journal() == null
         || operations.selection().journal() == null) {
         throw new IllegalStateException("an operation wrote before its journal became durable");
      }
      requireBlock(requiredLevel(server, Level.OVERWORLD), PLACEMENT_POS, Blocks.GOLD_BLOCK, "placement partial write");
      requireBlock(requiredLevel(server, Level.NETHER), WORKSPACE_POS, Blocks.DIAMOND_BLOCK, "workspace partial write");
      requireBlock(requiredLevel(server, Level.END), SELECTION_TARGET, Blocks.GOLD_BLOCK, "selection partial write");
      requireThreeUnsealedJournals(server);
      if (!server.saveAllChunks(true, true, true)) {
         throw new IllegalStateException("partial world save failed");
      }
      IOUtilities.waitUntilIOWorkerComplete();
      Files.writeString(CRASH_MARKER, "READY\n");
      Runtime.getRuntime().halt(17);
   }

   private static void requireThreeUnsealedJournals(MinecraftServer server) throws IOException {
      Path directory = server.getWorldPath(LevelResource.ROOT).resolve("fastformer-recovery");
      if (!Files.isDirectory(directory)) {
         throw new IllegalStateException("recovery directory was not created");
      }
      try (var entries = Files.list(directory)) {
         List<Path> journals = entries.toList();
         if (journals.size() != 3
            || journals.stream().anyMatch(path -> !Files.isDirectory(path)
               || !Files.isRegularFile(path.resolve("manifest.dat"))
               || !Files.isRegularFile(path.resolve("segment-000000.dat"))
               || Files.exists(path.resolve("seal.done")))) {
            throw new IllegalStateException("expected exactly three durable unsealed journals");
         }
      }
   }

   private static void advancePlacement(CrashOperations operations, ServerLevel level) {
      PlacementTask task = operations.placement();
      if (task.hasWrites()) {
         return;
      }
      if (!task.prepare() || !task.ensureMemoryReservation() || !task.acquireLease(operations.placementContext())) {
         return;
      }
      if (!task.snapshotsComplete()) {
         task.validateSnapshots(level, oneCellBudget());
         return;
      }
      JournalPreparation journal = task.prepareJournal(operations.placementContext());
      if (journal != JournalPreparation.READY || !task.blocks().hasNext()) {
         return;
      }
      BlockPos position = task.blocks().next();
      task.consumed();
      task.place(operations.placementContext(), level, position);
   }

   private static void advanceWorkspace(CrashOperations operations, ServerLevel level) {
      if (operations.workspace().hasWrites()) {
         return;
      }
      if (!operations.workspace().acquireLease(operations.workspaceContext())) {
         return;
      }
      OperationTaskResult result = operations.workspace().tick(
         operations.workspaceContext(), level, oneCellBudget()
      );
      requireActiveUntilWrite("workspace", result, operations.workspace().hasWrites());
   }

   private static void advanceSelection(CrashOperations operations, ServerLevel level) {
      if (operations.selection().hasWrites()) {
         return;
      }
      if (!operations.selection().acquireLease(operations.selectionContext())) {
         return;
      }
      OperationTaskResult result = operations.selection().tick(
         operations.selectionContext(), level, oneCellBudget()
      );
      requireActiveUntilWrite("selection", result, operations.selection().hasWrites());
   }

   private static void requireActiveUntilWrite(String name, OperationTaskResult result, boolean written) {
      if (!written && result != OperationTaskResult.ACTIVE) {
         throw new IllegalStateException(name + " ended before its first write: " + result);
      }
   }

   private static void verifyRecovery(MinecraftServer server) throws IOException {
      requireBlock(requiredLevel(server, Level.OVERWORLD), PLACEMENT_POS, Blocks.STONE, "placement");
      requireBlock(requiredLevel(server, Level.NETHER), WORKSPACE_POS, Blocks.STONE, "workspace");
      requireBlock(requiredLevel(server, Level.END), SELECTION_SOURCE, Blocks.GOLD_BLOCK, "selection source");
      requireBlock(requiredLevel(server, Level.END), SELECTION_TARGET, Blocks.STONE, "selection target");
      Path journalDirectory = server.getWorldPath(LevelResource.ROOT).resolve("fastformer-recovery");
      if (Files.isDirectory(journalDirectory)) {
         try (var journals = Files.list(journalDirectory)) {
            if (journals.findAny().isPresent()) {
               throw new IllegalStateException("startup recovery left journal entries behind");
            }
         }
      }
      if (!PersistentRecoveryJournal.writesAllowed()) {
         throw new IllegalStateException("startup recovery left writes blocked");
      }
      if (WorldWriteCoordinator.busy(server, Level.OVERWORLD)
         || WorldWriteCoordinator.busy(server, Level.NETHER)
         || WorldWriteCoordinator.busy(server, Level.END)) {
         throw new IllegalStateException("startup recovery left a dimension lease busy");
      }
      if (MemoryReservation.reservedBytes() != 0L) {
         throw new IllegalStateException(
            "startup recovery retained " + MemoryReservation.reservedBytes() + " reserved bytes"
         );
      }
      finish(server, "PASS\n");
   }

   private static void setBaseline(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
      level.getChunkAt(pos);
      level.setBlock(pos, state, 2);
      if (!level.getBlockState(pos).equals(state)) {
         throw new IllegalStateException("could not set baseline at " + pos.toShortString());
      }
   }

   private static void requireBlock(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block expected, String name) {
      level.getChunkAt(pos);
      if (!level.getBlockState(pos).is(expected)) {
         throw new IllegalStateException(name + " was not restored at " + pos.toShortString());
      }
   }

   private static ServerLevel requiredLevel(MinecraftServer server, net.minecraft.resources.ResourceKey<Level> dimension) {
      ServerLevel level = server.getLevel(dimension);
      if (level == null) {
         throw new IllegalStateException("dimension is not loaded: " + dimension.location());
      }
      return level;
   }

   private static WorldTaskBudget oneCellBudget() {
      return WorldTaskBudget.testing(1, 1, 0L, () -> 0L);
   }

   private static void finish(MinecraftServer server, String result) {
      mode = Mode.DISABLED;
      try {
         Files.writeString(RESULT, result.endsWith("\n") ? result : result + "\n");
      } catch (IOException ignored) {
      }
      server.halt(false);
   }

   private enum Mode {
      DISABLED,
      CRASH,
      VERIFY
   }

   private record CrashOperations(
      PlacementTask placement,
      WorldTaskContext placementContext,
      ClientWorkspacePlacementTask workspace,
      WorldTaskContext workspaceContext,
      SelectionOperationTask selection,
      WorldTaskContext selectionContext
   ) {
      boolean allWritten() {
         return placement.hasWrites() && workspace.hasWrites() && selection.hasWrites();
      }
   }
}
