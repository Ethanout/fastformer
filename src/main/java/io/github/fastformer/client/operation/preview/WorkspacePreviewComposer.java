package io.github.fastformer.client.operation.preview;

import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.operation.transform.VoxelRotation;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.BlockPositionMaps;
import io.github.fastformer.fastplace.geometry.WorkspaceGeometryBudget;
import io.github.fastformer.fastplace.geometry.WorkspaceGeometryCost;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Resolves workspace transforms into the exact voxel maps used by rendering and submission. */
public final class WorkspacePreviewComposer {
   public static final int CLIENT_RENDER_BLOCK_LIMIT = 100_000;
   public static final int CLIENT_INTERACTION_BLOCK_LIMIT = 200_000;

   private WorkspacePreviewComposer() {
   }

   public static <T> Composition<T> composeValues(
      Map<BlockPos, T> source, WorkspaceTransform transform, CompositionBudget budget
   ) {
      return compose(source, transform, VoxelRotation.positionOnly(), budget);
   }

   public static Composition<ClientBlockSnapshot> composeSnapshots(
      Map<BlockPos, ClientBlockSnapshot> source, WorkspaceTransform transform, CompositionBudget budget
   ) {
      return compose(source, transform, VoxelRotation.snapshotValues(), budget);
   }

   /**
    * Test and legacy helper. Production callers must use {@link #composeValues} or
    * {@link #composeSnapshots} and handle {@link Composition.OverBudget}.
    */
   public static <T> Map<BlockPos, T> resolveValues(Map<BlockPos, T> source, WorkspaceTransform transform) {
      return requireComposed(composeValues(source, transform, CompositionBudget.INTERACTION));
   }

   /**
    * Test and legacy helper. Production callers must use {@link #composeSnapshots}
    * and handle {@link Composition.OverBudget}.
    */
   public static Map<BlockPos, ClientBlockSnapshot> resolveSnapshots(
      Map<BlockPos, ClientBlockSnapshot> source, WorkspaceTransform transform
   ) {
      return requireComposed(composeSnapshots(source, transform, CompositionBudget.INTERACTION));
   }

   private static <T> Map<BlockPos, T> requireComposed(Composition<T> composition) {
      if (composition instanceof Composition.Composed<T> composed) {
         return composed.values();
      }
      throw new IllegalStateException("composition exceeded its budget");
   }

   /**
    * The geometry frame one transform uses: where the scale stage anchored, how far repeats
    * step, and which pivot each rotation axis uses.
    *
    * <p>Render geometry applies this frame to a selection envelope so the outline and Gizmo
    * stay on the blocks they describe. Deriving a centre from the selection box instead would
    * separate them whenever the selection holds air, uses repeats, rescales, or rotates about
    * more than one axis.
    *
    * <p>Returns null when the part is too large for the render budget. The caller then keeps
    * its conservative envelope.
    */
   public static <T> GeometryFrame geometryFrame(Map<BlockPos, T> source, WorkspaceTransform transform) {
      if (source == null || source.isEmpty() || transform == null || !transform.hasEffect()) {
         return null;
      }
      if (!canResolveForRendering(source, transform)) {
         return null;
      }
      return frameFrom(composeValues(source, transform, CompositionBudget.RENDER));
   }

   /** How one transform anchors, steps and rotates the cells it produces. */
   public record GeometryFrame(Vec3 scaleAnchor, BlockPos repeatStrideCells, List<VoxelRotation.RotationStep> rotationSteps) {
      public GeometryFrame {
         rotationSteps = rotationSteps == null ? List.of() : List.copyOf(rotationSteps);
      }
   }

   /** A weak identity key prevents this small cache from retaining a part's source block map. */
   private static final class FrameEntry {
      private final WeakReference<ClientSelectionPart> part;
      private GeometryFrame frame;
      private boolean computed;

      private FrameEntry(ClientSelectionPart part) {
         this.part = new WeakReference<>(part);
      }

      private boolean belongsTo(ClientSelectionPart candidate) {
         return this.part.get() == candidate;
      }
   }

   /** A cached resolved map is bounded separately because it can contain up to 100,000 cells. */
   private static final class ResolvedEntry {
      private final WeakReference<ClientSelectionPart> part;
      private final Map<BlockPos, ClientBlockSnapshot> values;

      private ResolvedEntry(ClientSelectionPart part, Map<BlockPos, ClientBlockSnapshot> values) {
         this.part = new WeakReference<>(part);
         this.values = values;
      }

      private boolean belongsTo(ClientSelectionPart candidate) {
         return this.part.get() == candidate;
      }
   }

   private static final int FRAME_CACHE_LIMIT = 256;
   private static final int RESOLVED_CACHE_ENTRY_LIMIT = 64;
   private static final int RESOLVED_CACHE_BLOCK_LIMIT = CLIENT_RENDER_BLOCK_LIMIT;
   private static final Map<Integer, FrameEntry> FRAME_CACHE = new LinkedHashMap<>(16, 0.75F, true);
   private static final Map<Integer, ResolvedEntry> RESOLVED_CACHE = new LinkedHashMap<>(16, 0.75F, true);
   private static int resolvedCacheBlocks;

   /**
    * The frame this part's transform uses, computed once per part instance.
    *
    * <p>Every render frame asks for the same frame from several places. Computing it once per
    * instance removes the repeated full voxel expansion without changing the value.
    *
    * @return the frame, or null when the part is absent, has no effect, or is outside the render budget
    */
   public static GeometryFrame frameForPart(ClientSelectionPart part) {
      if (part == null || !canResolveForFrame(part)) {
         return null;
      }
      FrameEntry entry = frameEntry(part);
      if (!entry.computed) {
         cacheComposed(part, composeSnapshots(part.blocks(), part.transform(), CompositionBudget.RENDER));
      }
      return entry.frame;
   }

   /**
    * The transformed blocks of one part, computed once per part instance.
    *
    * <p>Equal to {@link #resolve(ClientSelectionPart)}. Geometry consumers share this result
    * instead of each running the pipeline again.
    */
   public static Map<BlockPos, ClientBlockSnapshot> resolvedForPart(ClientSelectionPart part) {
      if (part == null) {
         return Map.of();
      }
      ResolvedEntry entry = resolvedEntry(part);
      if (entry != null) {
         return entry.values;
      }
      Composition<ClientBlockSnapshot> composition = composeSnapshots(
         part.blocks(), part.transform(), CompositionBudget.RENDER
      );
      if (!(composition instanceof Composition.Composed<ClientBlockSnapshot> composed)) {
         return Map.of();
      }
      if (canResolveForFrame(part)) {
         cacheFrame(part, composed.frame());
      }
      cacheResolved(part, composed.values());
      return composed.values();
   }

   /** Drops every cached resolution. Call it when a workspace session ends. */
   public static void invalidatePartGeometry() {
      FRAME_CACHE.clear();
      RESOLVED_CACHE.clear();
      resolvedCacheBlocks = 0;
   }

   /** Bounds the weak-reference tombstones even when a session does not end cleanly. */
   public static void prunePartGeometry() {
      pruneCollectedEntries();
   }

   private static boolean canResolveForFrame(ClientSelectionPart part) {
      return frameChangesGeometry(part.transform())
         && canResolveForRendering(part.blocks(), part.transform());
   }

   private static FrameEntry frameEntry(ClientSelectionPart part) {
      FrameEntry entry = FRAME_CACHE.get(part.id());
      if (entry != null && entry.belongsTo(part)) {
         return entry;
      }
      entry = new FrameEntry(part);
      FRAME_CACHE.put(part.id(), entry);
      trimFrameCache();
      return entry;
   }

   private static ResolvedEntry resolvedEntry(ClientSelectionPart part) {
      ResolvedEntry entry = RESOLVED_CACHE.get(part.id());
      if (entry == null || entry.belongsTo(part)) {
         return entry;
      }
      removeResolved(part.id(), entry);
      return null;
   }

   private static void cacheComposed(ClientSelectionPart part, Composition<ClientBlockSnapshot> composition) {
      if (composition instanceof Composition.Composed<ClientBlockSnapshot> composed) {
         cacheFrame(part, composed.frame());
         cacheResolved(part, composed.values());
      }
   }

   private static void cacheFrame(ClientSelectionPart part, GeometryFrame frame) {
      FrameEntry entry = frameEntry(part);
      entry.frame = frame;
      entry.computed = true;
   }

   private static void cacheResolved(ClientSelectionPart part, Map<BlockPos, ClientBlockSnapshot> values) {
      if (values.size() > RESOLVED_CACHE_BLOCK_LIMIT) {
         return;
      }
      ResolvedEntry existing = RESOLVED_CACHE.get(part.id());
      if (existing != null) {
         removeResolved(part.id(), existing);
      }
      while (!RESOLVED_CACHE.isEmpty() && (RESOLVED_CACHE.size() >= RESOLVED_CACHE_ENTRY_LIMIT
         || resolvedCacheBlocks + values.size() > RESOLVED_CACHE_BLOCK_LIMIT)) {
         removeOldestResolved();
      }
      RESOLVED_CACHE.put(part.id(), new ResolvedEntry(part, values));
      resolvedCacheBlocks += values.size();
   }

   private static void trimFrameCache() {
      while (FRAME_CACHE.size() > FRAME_CACHE_LIMIT) {
         Iterator<Integer> entries = FRAME_CACHE.keySet().iterator();
         entries.next();
         entries.remove();
      }
   }

   /** Removes entries whose weak part was collected between render frames. */
   private static void pruneCollectedEntries() {
      FRAME_CACHE.entrySet().removeIf(entry -> entry.getValue().part.get() == null);
      Iterator<Map.Entry<Integer, ResolvedEntry>> resolved = RESOLVED_CACHE.entrySet().iterator();
      while (resolved.hasNext()) {
         Map.Entry<Integer, ResolvedEntry> entry = resolved.next();
         if (entry.getValue().part.get() == null) {
            resolvedCacheBlocks -= entry.getValue().values.size();
            resolved.remove();
         }
      }
      if (resolvedCacheBlocks < 0) {
         resolvedCacheBlocks = 0;
      }
   }

   private static void removeOldestResolved() {
      Iterator<Map.Entry<Integer, ResolvedEntry>> entries = RESOLVED_CACHE.entrySet().iterator();
      Map.Entry<Integer, ResolvedEntry> oldest = entries.next();
      resolvedCacheBlocks -= oldest.getValue().values.size();
      entries.remove();
   }

   private static void removeResolved(int id, ResolvedEntry entry) {
      if (RESOLVED_CACHE.remove(id, entry)) {
         resolvedCacheBlocks -= entry.values.size();
      }
   }

   static int frameCacheSizeForTesting() {
      return FRAME_CACHE.size();
   }

   static int resolvedCacheSizeForTesting() {
      return RESOLVED_CACHE.size();
   }

   static int resolvedCacheBlocksForTesting() {
      return resolvedCacheBlocks;
   }

   static int frameCacheLimitForTesting() {
      return FRAME_CACHE_LIMIT;
   }

   static int resolvedCacheEntryLimitForTesting() {
      return RESOLVED_CACHE_ENTRY_LIMIT;
   }

   /**
    * Whether a frame can change the envelope at all.
    *
    * <p>A pure translation keeps the selection centre, the selection width and no rotation step,
    * so the frame is not requested and the caller stays on the constant time path.
    */
   public static boolean frameChangesGeometry(WorkspaceTransform transform) {
      if (transform == null || !transform.hasEffect()) {
         return false;
      }
      return !transform.rotation().equals(Vec3.ZERO)
         || !transform.scale().equals(new Vec3(1.0, 1.0, 1.0))
         || !transform.repeats().equals(OperationStackRegion.origin());
   }

   private static <T> GeometryFrame frameFrom(Composition<T> composition) {
       return composition instanceof Composition.Composed<T> composed ? composed.frame() : null;
   }

   private static <T> Composition<T> compose(
       Map<BlockPos, T> source,
       WorkspaceTransform transform,
       VoxelRotation.ValueRotation<T> valueRotation,
       CompositionBudget budget
   ) {
       if (budget == null) {
          throw new IllegalArgumentException("composition budget is required");
       }
       CompositionCounter counter = new CompositionCounter(budget);
       return compose(source, transform, valueRotation, counter);
   }

   static <T> Composition<T> compose(
       Map<BlockPos, T> source,
       WorkspaceTransform transform,
       VoxelRotation.ValueRotation<T> valueRotation,
       CompositionCounter counter
   ) {
       CompositionBudget budget = counter.budget();
       if (source == null || source.isEmpty()) {
          return new Composition.Composed<>(Map.of(), null);
       }
       Composition.OverBudget<T> sourceCap = counter.checkOutput(source.size());
       if (sourceCap != null) {
          return sourceCap;
       }
       if (transform == null || transform.isIdentity()) {
          Composition.OverBudget<T> work = counter.addWork(source.size());
          if (work != null) {
             return work;
          }
          return new Composition.Composed<>(source, null);
       }
       ScaleStage<T> scaled = scaleValues(source, transform.scale(), counter);
       if (scaled.overBudget() != null) {
          return scaled.overBudget();
       }
       // An empty sampling result is a legal answer, not a failure. A downscale can sample only
       // empty cells, and a scaled part must propagate that emptiness to the caller.
       OccupiedBlockBounds scaledBounds = OccupiedBlockBounds.from(scaled.values().keySet()).orElse(null);
       if (scaledBounds == null) {
          return new Composition.Composed<>(Map.of(), null);
       }
       OccupiedBlockBounds sourceBounds = OccupiedBlockBounds.from(source.keySet()).orElse(null);
       Vec3 scaleAnchor = transformedExtents(sourceBounds, transform.scale()) ? sourceBounds.center() : null;
       BlockPos stride = transform.repeatStride();
       BlockPos repeatStrideCells = new BlockPos(
          stride.getX() == 0 ? scaledBounds.width(AxisGizmo.Axis.X) : Math.abs(stride.getX()),
          stride.getY() == 0 ? scaledBounds.width(AxisGizmo.Axis.Y) : Math.abs(stride.getY()),
          stride.getZ() == 0 ? scaledBounds.width(AxisGizmo.Axis.Z) : Math.abs(stride.getZ())
       );
       LinkedHashMap<BlockPos, T> repeated = new LinkedHashMap<>();
       long[] visitedRepeats = {0L};
       AtomicReference<Composition.OverBudget<T>> repeatFailure = new AtomicReference<>();
       long remainingWork = Math.max(0L, budget.maxWorkSteps() - counter.work());
       transform.repeats().visitRepetitions(Math.max(1L, remainingWork), repetition -> {
          visitedRepeats[0]++;
          BlockPos displacement = new BlockPos(
             repetition.getX() * repeatStrideCells.getX(),
             repetition.getY() * repeatStrideCells.getY(),
             repetition.getZ() * repeatStrideCells.getZ()
          );
          for (Map.Entry<BlockPos, T> entry : scaled.values().entrySet()) {
             Composition.OverBudget<T> work = counter.addWork(1L);
             if (work != null) {
                repeatFailure.set(work);
                return false;
             }
             repeated.put(entry.getKey().offset(displacement), entry.getValue());
             Composition.OverBudget<T> output = counter.checkOutput(repeated.size());
             if (output != null) {
                repeatFailure.set(output);
                return false;
             }
          }
          return true;
       });
       if (repeatFailure.get() != null) {
          return repeatFailure.get();
       }
       if (visitedRepeats[0] < transform.repeats().cellCount()) {
          return new Composition.OverBudget<>(Composition.Limit.WORK, budget.maxWorkSteps(), Long.MAX_VALUE);
       }
       VoxelRotation.BoundedRotation<T> rotation = VoxelRotation.rotateStage(
          repeated, transform.rotation(), valueRotation, counter
       );
       if (rotation.overBudget() != null) {
          return rotation.overBudget();
       }
       Map<BlockPos, T> rotated = rotation.result().values();
       LinkedHashMap<BlockPos, T> translated = new LinkedHashMap<>();
       Vec3 offset = transform.translation();
       for (Map.Entry<BlockPos, T> entry : rotated.entrySet()) {
          Composition.OverBudget<T> work = counter.addWork(1L);
          if (work != null) {
             return work;
          }
          Vec3 center = Vec3.atCenterOf(entry.getKey()).add(offset);
          translated.put(BlockPos.containing(center.x, center.y, center.z), entry.getValue());
          Composition.OverBudget<T> output = counter.checkOutput(translated.size());
          if (output != null) {
             return output;
          }
       }
       GeometryFrame frame = new GeometryFrame(
          scaleAnchor,
          visitedRepeats[0] > 1L ? repeatStrideCells : null,
          rotation.result().steps()
       );
       return new Composition.Composed<>(translated, frame);
   }

   private record ScaleStage<T>(Map<BlockPos, T> values, Composition.OverBudget<T> overBudget) {
   }
   /** True when the scale stage changes the cell extents and therefore re-anchors the result. */
   private static boolean transformedExtents(OccupiedBlockBounds sourceBounds, Vec3 scale) {
      if (sourceBounds == null || scale == null) {
         return false;
      }
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         long target = scaledExtent(sourceBounds.width(axis), component(scale, axis));
         if (target != sourceBounds.width(axis)) {
            return true;
         }
      }
      return false;
   }

   private static double component(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static long scaledExtent(int sourceExtent, double scale) {
      double extent = sourceExtent * scale;
      return Double.isFinite(extent) ? Math.max(1L, Math.round(extent)) : Long.MAX_VALUE;
   }

   static <T> Map<BlockPos, T> scaleValues(Map<BlockPos, T> source, Vec3 scale) {
       ScaleStage<T> scaled = scaleValues(source, scale, new CompositionCounter(CompositionBudget.INTERACTION));
       if (scaled.overBudget() != null) {
          throw new IllegalStateException("scale exceeded its budget");
       }
       return scaled.values();
   }

   private static <T> ScaleStage<T> scaleValues(
       Map<BlockPos, T> source, Vec3 scale, CompositionCounter counter
   ) {
       if (source == null || source.isEmpty()) {
          return new ScaleStage<>(Map.of(), null);
       }
       OccupiedBlockBounds bounds = OccupiedBlockBounds.from(source.keySet()).orElse(null);
       if (bounds == null) {
          return new ScaleStage<>(Map.of(), null);
       }
       int sourceX = bounds.width(AxisGizmo.Axis.X);
       int sourceY = bounds.width(AxisGizmo.Axis.Y);
       int sourceZ = bounds.width(AxisGizmo.Axis.Z);
       long extentX = scaledExtent(sourceX, scale.x);
       long extentY = scaledExtent(sourceY, scale.y);
       long extentZ = scaledExtent(sourceZ, scale.z);
       long availableWork = counter.budget().maxWorkSteps() - counter.work();
       if (extentX > Integer.MAX_VALUE || extentY > Integer.MAX_VALUE || extentZ > Integer.MAX_VALUE) {
          return new ScaleStage<>(null, new Composition.OverBudget<>(
             Composition.Limit.WORK, counter.budget().maxWorkSteps(), Long.MAX_VALUE
          ));
       }
       int targetX = (int)extentX;
       int targetY = (int)extentY;
       int targetZ = (int)extentZ;
       if (targetX == sourceX && targetY == sourceY && targetZ == sourceZ) {
          return new ScaleStage<>(source, null);
       }
       if (extentX > availableWork / extentY || extentX * extentY > availableWork / extentZ) {
          return new ScaleStage<>(null, new Composition.OverBudget<>(
             Composition.Limit.WORK, counter.budget().maxWorkSteps(), Long.MAX_VALUE
          ));
       }
       long scanVolume = (long)targetX * (long)targetY * (long)targetZ;
       Composition.OverBudget<T> scan = counter.addWork(scanVolume);
       if (scan != null) {
          return new ScaleStage<>(null, scan);
       }
       // Scan volume is work. A sparse downscale can still write nothing.
       Vec3 center = bounds.center();
       int minX = (int)Math.floor(center.x - targetX * 0.5);
       int minY = (int)Math.floor(center.y - targetY * 0.5);
       int minZ = (int)Math.floor(center.z - targetZ * 0.5);
       LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
       for (int x = 0; x < targetX; x++) {
          int sourceOffsetX = Math.min(sourceX - 1, (int)Math.floor((x + 0.5) * sourceX / targetX));
          for (int y = 0; y < targetY; y++) {
             int sourceOffsetY = Math.min(sourceY - 1, (int)Math.floor((y + 0.5) * sourceY / targetY));
             for (int z = 0; z < targetZ; z++) {
                int sourceOffsetZ = Math.min(sourceZ - 1, (int)Math.floor((z + 0.5) * sourceZ / targetZ));
                T value = source.get(new BlockPos(
                   bounds.min().getX() + sourceOffsetX,
                   bounds.min().getY() + sourceOffsetY,
                   bounds.min().getZ() + sourceOffsetZ
                ));
                if (value != null) {
                   result.put(new BlockPos(minX + x, minY + y, minZ + z), value);
                   Composition.OverBudget<T> output = counter.checkOutput(result.size());
                   if (output != null) {
                      return new ScaleStage<>(null, output);
                   }
                }
             }
          }
       }
       return new ScaleStage<>(BlockPositionMaps.copyOf(result), null);
   }
   public static <T> ComposedValues<T> composeValues(Map<Integer, Map<BlockPos, T>> parts) {
      LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
      LinkedHashSet<BlockPos> overlaps = new LinkedHashSet<>();
      List<Integer> ids = new ArrayList<>(parts.keySet());
      ids.sort(Comparator.naturalOrder());
      for (int id : ids) {
         parts.get(id).forEach((pos, value) -> {
            if (result.containsKey(pos)) {
               overlaps.add(pos.immutable());
            }
            result.put(pos.immutable(), value);
         });
      }
      return new ComposedValues<>(BlockPositionMaps.copyOf(result), Set.copyOf(overlaps));
   }

   public static Map<BlockPos, ClientBlockSnapshot> resolve(ClientSelectionPart part) {
      return requireComposed(compose(part, CompositionBudget.INTERACTION));
   }

   public static Composition<ClientBlockSnapshot> compose(ClientSelectionPart part, CompositionBudget budget) {
      if (part == null) {
         return new Composition.Composed<>(Map.of(), null);
      }
      return composeSnapshots(part.blocks(), part.transform(), budget);
   }

   /** Resolves only workspaces small enough to render without stalling the client thread. */
   public static Map<BlockPos, ClientBlockSnapshot> resolveForRendering(ClientSelectionPart part) {
      if (part == null) {
         return Map.of();
      }
      Composition<ClientBlockSnapshot> composition = composeSnapshots(
         part.blocks(), part.transform(), CompositionBudget.RENDER
      );
      return composition instanceof Composition.Composed<ClientBlockSnapshot> composed
         ? composed.values()
         : Map.of();
   }

   public static boolean canResolveForRendering(Map<BlockPos, ?> source, WorkspaceTransform transform) {
      if (source == null || source.isEmpty() || transform == null) {
         return true;
      }
      WorkspaceGeometryCost.Cost cost = WorkspaceGeometryCost.of(source.keySet(), transform);
      return cost == null || WorkspaceGeometryBudget.fits(CLIENT_RENDER_BLOCK_LIMIT, cost);
   }

   public record ComposedValues<T>(Map<BlockPos, T> values, Set<BlockPos> overlaps) {
   }
}
