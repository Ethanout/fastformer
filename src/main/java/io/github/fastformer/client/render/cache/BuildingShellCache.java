package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Caches the generated shell mesh for one confirmed or pending block set. */
public final class BuildingShellCache {
   private static final long REGULAR_CULL_GROUP = Long.MIN_VALUE;
   private final boolean pending;
   private final boolean copyInputs;
   private final ThreadPoolExecutor executor;
   private final Consumer<Throwable> failureLogger;
   private BlockState state;
   private Map<net.minecraft.core.BlockPos, BlockState> stateOverrides = Map.of();
   private Set<net.minecraft.core.BlockPos> blocks = Set.of();
   private Set<net.minecraft.core.BlockPos> shapeEnvironment = Set.of();
   private Map<net.minecraft.core.BlockPos, BuildingSpecialBlock> specialStyles = Map.of();
   private boolean playerShift;
   private ShapeShellMesh.Mesh mesh = ShapeShellMesh.Mesh.empty();
   private Future<MeshResult> future;
   private long version;

   public BuildingShellCache(boolean pending) {
      this(pending, true, null, ignored -> {});
   }

   private BuildingShellCache(boolean pending, boolean copyInputs, ThreadPoolExecutor executor, Consumer<Throwable> failureLogger) {
      this.pending = pending;
      this.copyInputs = copyInputs;
      this.executor = executor;
      this.failureLogger = failureLogger;
   }

   /** Creates a cache for collections already owned as immutable render snapshots. */
   public static BuildingShellCache forImmutableSnapshots(boolean pending) {
      return new BuildingShellCache(pending, false, null, ignored -> {});
   }

   /** Creates a cache that samples shapes on the caller thread and builds large immutable snapshots in the background. */
   public static BuildingShellCache forImmutableSnapshots(
      boolean pending, ThreadPoolExecutor executor, Consumer<Throwable> failureLogger
   ) {
      return new BuildingShellCache(pending, false, executor, failureLogger);
   }

   public ShapeShellMesh.Mesh mesh(
      BlockGetter previewLevel,
      BlockState state,
      Map<net.minecraft.core.BlockPos, BlockState> stateOverrides,
      CollisionContext collision,
      Set<net.minecraft.core.BlockPos> blocks,
      Set<net.minecraft.core.BlockPos> shapeEnvironment,
      Map<net.minecraft.core.BlockPos, BuildingSpecialBlock> specialStyles,
      boolean playerShift
   ) {
      publishCompletedMesh();
      if (this.state == state
         && this.blocks == blocks
         && this.stateOverrides == stateOverrides
         && this.shapeEnvironment == shapeEnvironment
         && this.specialStyles == specialStyles
         && this.playerShift == playerShift) {
         return this.mesh;
      }

      Set<net.minecraft.core.BlockPos> nextBlocks = this.copyInputs ? BlockPositionSets.copyOf(blocks) : blocks;
      Map<net.minecraft.core.BlockPos, BlockState> nextOverrides = this.copyInputs ? BlockPositionMaps.copyOf(stateOverrides) : stateOverrides;
      Set<net.minecraft.core.BlockPos> nextEnvironment = this.copyInputs ? BlockPositionSets.copyOf(shapeEnvironment) : shapeEnvironment;
      Map<net.minecraft.core.BlockPos, BuildingSpecialBlock> nextStyles = this.copyInputs ? BlockPositionMaps.copyOf(specialStyles) : specialStyles;

      boolean sameInput = this.state == state
         && this.blocks.equals(nextBlocks)
         && this.stateOverrides.equals(nextOverrides)
         && this.shapeEnvironment.equals(nextEnvironment)
         && this.specialStyles.equals(nextStyles)
         && this.playerShift == playerShift;
      if (sameInput) {
         return this.mesh;
      }

      // Shape and level access belongs to the render thread. The resulting parts are immutable value data.
      ArrayList<ShapeShellMesh.Part> parts = new ArrayList<>(nextBlocks.size());
      for (net.minecraft.core.BlockPos pos : nextBlocks) {
         BlockState stateAt = nextOverrides.getOrDefault(pos, state);
         List<AABB> boxes = stateAt == null
            ? List.of(new AABB(pos))
            : stateAt.getShape(previewLevel, pos, collision).toAabbs().stream()
               .map(box -> box.move(pos))
               .toList();
         if (boxes.isEmpty()) {
            continue;
         }

         BuildingSpecialBlock special = this.pending ? null : nextStyles.get(pos);
         ShapeShellMesh.Color faceColor = special == null
            ? ShapeShellMesh.Color.WHITE
            : new ShapeShellMesh.Color(special.style().red(), special.style().green(), special.style().blue());
         ShapeShellMesh.Color outlineColor = this.pending
            ? ShapeShellMesh.Color.WHITE
            : ShapeShellMesh.Color.BLACK;
         Set<Direction> hiddenFaces = java.util.EnumSet.noneOf(Direction.class);
         if (boxes.size() == 1 && boxes.getFirst().equals(new AABB(pos))) {
            for (Direction direction : Direction.values()) {
               net.minecraft.core.BlockPos neighbor = pos.relative(direction);
               if (nextEnvironment.contains(neighbor)
                  && sharesCullGroup(special, nextStyles.get(neighbor))) {
                  hiddenFaces.add(direction);
               }
            }
            // Interior full blocks cannot contribute a visible face or edge.
            // Drop them before mesh construction so large solid previews scale
            // with their surface instead of their volume.
            if (hiddenFaces.size() == Direction.values().length) {
               continue;
            }
         }
         long cullGroup = special == null ? REGULAR_CULL_GROUP : pos.asLong();
         parts.add(new ShapeShellMesh.Part(boxes, faceColor, outlineColor, true, hiddenFaces, cullGroup));
      }
      this.state = state;
      this.blocks = nextBlocks;
      this.stateOverrides = nextOverrides;
      this.shapeEnvironment = nextEnvironment;
      this.specialStyles = nextStyles;
      this.playerShift = playerShift;
      this.version++;
      cancelFuture();
      buildOrSchedule(List.copyOf(parts), this.version);
      publishCompletedMesh();
      return this.mesh;
   }

   private static boolean sharesCullGroup(BuildingSpecialBlock first, BuildingSpecialBlock second) {
      return first == null && second == null;
   }

   private void buildOrSchedule(List<ShapeShellMesh.Part> parts, long requestedVersion) {
      if (this.executor == null || PreviewAsyncPolicy.meshSynchronously(parts.size())) {
         this.mesh = ShapeShellMesh.build(parts);
         return;
      }
      try {
         this.future = this.executor.submit(() -> new MeshResult(requestedVersion, ShapeShellMesh.build(parts)));
      } catch (java.util.concurrent.RejectedExecutionException exception) {
         discardInputsWithoutAMesh();
         this.failureLogger.accept(exception);
      }
   }

   private void publishCompletedMesh() {
      if (this.future == null || !this.future.isDone()) {
         return;
      }
      Future<MeshResult> completedFuture = this.future;
      this.future = null;
      try {
         MeshResult result = completedFuture.get();
         if (result.version() == this.version) {
            this.mesh = result.mesh();
         }
      } catch (CancellationException ignored) {
         // Superseded futures are removed by cancelFuture and cannot reach this path.
         discardInputsWithoutAMesh();
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         discardInputsWithoutAMesh();
      } catch (ExecutionException exception) {
         Throwable cause = exception.getCause();
         discardInputsWithoutAMesh();
         if (!(cause instanceof CancellationException)) {
            this.failureLogger.accept(cause);
         }
      }
   }

   /**
    * A failed background build produced no mesh for the inputs it committed. Forget
    * those inputs so the next frame rebuilds them. Without this step the identity
    * check would keep returning the previous input set's mesh and never retry.
    */
   private void discardInputsWithoutAMesh() {
      clear();
   }

   private void cancelFuture() {
      if (this.future == null) {
         return;
      }
      Future<MeshResult> cancelled = this.future;
      this.future = null;
      cancelled.cancel(true);
      if (cancelled instanceof Runnable task) {
         this.executor.remove(task);
      }
   }

   public void clear() {
      this.state = null;
      this.stateOverrides = Map.of();
      this.blocks = Set.of();
      this.shapeEnvironment = Set.of();
      this.specialStyles = Map.of();
      this.playerShift = false;
      this.version++;
      cancelFuture();
      this.mesh = ShapeShellMesh.Mesh.empty();
   }

   private record MeshResult(long version, ShapeShellMesh.Mesh mesh) {
   }
}
