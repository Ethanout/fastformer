import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import java.util.*;
import java.lang.reflect.*;

class TiltedBoxDiagnostics {
   static final Class<?> GENERATOR;
   static final Class<?> VOLUME;
   static final Method CREATE_VOLUME;
   static final Method WORKER_SOLID;
   static final Method WORKER_BLOCKS;
   static final Method FILL_ENCLOSED;
   static final Method OUTLINE;
   static final Method EXPOSE_OUTLINE;
   static final Method CONTAINS;
   static final int[][] NEIGHBORS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
   };

   static {
      try {
         GENERATOR = Class.forName("io.github.fastformer.fastplace.geometry.generation.TiltedBoxGenerator");
         VOLUME = Class.forName("io.github.fastformer.fastplace.geometry.generation.TiltedBoxGenerator$Volume");
         CREATE_VOLUME = VOLUME.getDeclaredMethod("create", List.class, Vec3.class);
         WORKER_SOLID = GENERATOR.getDeclaredMethod("workerSolid", List.class, Vec3.class, VOLUME, int.class);
         Class<?> worker = Class.forName("io.github.fastformer.fastplace.geometry.generation.TiltedBoxGenerator$WorkerSolid");
         WORKER_BLOCKS = worker.getDeclaredMethod("blocks");
         FILL_ENCLOSED = GENERATOR.getDeclaredMethod("fillEnclosedAir", Set.class);
         OUTLINE = GENERATOR.getDeclaredMethod("outline", List.class, Vec3.class, int.class);
         EXPOSE_OUTLINE = GENERATOR.getDeclaredMethod("exposeOwnedOutline", Set.class, Set.class, VOLUME);
         CONTAINS = VOLUME.getDeclaredMethod("contains", Vec3.class);
         for (Method method : List.of(
            CREATE_VOLUME, WORKER_SOLID, WORKER_BLOCKS, FILL_ENCLOSED, OUTLINE, EXPOSE_OUTLINE, CONTAINS
         )) {
            method.setAccessible(true);
         }
      } catch (ReflectiveOperationException exception) {
         throw new RuntimeException(exception);
      }
   }

   record Metrics(
      int rawSize,
      int filledSize,
      int finalSize,
      int canonicalSize,
      int filledCavities,
      int scanMissing,
      int scanMissingWithThreeNeighbors,
      int carved,
      int carvedCanonical,
      int buriedOutline,
      int removedComponents,
      int largestRemovedComponent
   ) {}

   static Metrics analyze(Vec3 edgeA, Vec3 edgeB, Vec3 extrusion) throws Exception {
      Vec3 first = new Vec3(0.5, 0.5, 0.5);
      List<Vec3> base = List.of(
         first,
         first.add(edgeA),
         first.add(edgeA).add(edgeB),
         first.add(edgeB)
      );
      Object volume = CREATE_VOLUME.invoke(null, base, extrusion);
      if (volume == null) {
         return null;
      }
      Object worker = WORKER_SOLID.invoke(null, base, extrusion, volume, Integer.MAX_VALUE);
      @SuppressWarnings("unchecked")
      Set<BlockPos> workerBlocks = (Set<BlockPos>) WORKER_BLOCKS.invoke(worker);
      LinkedHashSet<BlockPos> raw = new LinkedHashSet<>(workerBlocks);
      LinkedHashSet<BlockPos> filled = new LinkedHashSet<>(raw);
      FILL_ENCLOSED.invoke(null, filled);

      @SuppressWarnings("unchecked")
      Set<BlockPos> outline = (Set<BlockPos>) OUTLINE.invoke(null, base, extrusion, Integer.MAX_VALUE);
      int buriedOutline = (int) outline.stream()
         .filter(filled::contains)
         .filter(position -> !isBoundary(position, filled))
         .count();
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(filled);
      EXPOSE_OUTLINE.invoke(null, result, outline, volume);
      HashSet<BlockPos> removed = new HashSet<>(filled);
      removed.removeAll(result);

      ArrayList<Vec3> vertices = new ArrayList<>(base);
      base.forEach(vertex -> vertices.add(vertex.add(extrusion)));
      int minX = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.x).min().orElse(0.0)) - 1;
      int minY = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.y).min().orElse(0.0)) - 1;
      int minZ = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.z).min().orElse(0.0)) - 1;
      int maxX = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.x).max().orElse(0.0)) + 1;
      int maxY = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.y).max().orElse(0.0)) + 1;
      int maxZ = (int) Math.floor(vertices.stream().mapToDouble(vertex -> vertex.z).max().orElse(0.0)) + 1;
      HashSet<BlockPos> canonical = new HashSet<>();
      for (int x = minX; x <= maxX; x++) {
         for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
               BlockPos position = new BlockPos(x, y, z);
               if ((boolean) CONTAINS.invoke(volume, Vec3.atCenterOf(position))) {
                  canonical.add(position);
               }
            }
         }
      }
      HashSet<BlockPos> scanMissing = new HashSet<>(canonical);
      scanMissing.removeAll(filled);
      int denseScanMissing = (int) scanMissing.stream()
         .filter(position -> solidNeighborCount(position, filled) >= 3)
         .count();
      HashSet<BlockPos> carvedCanonical = new HashSet<>(removed);
      carvedCanonical.retainAll(canonical);
      int[] componentSizes = removedComponentSizes(removed);
      return new Metrics(
         raw.size(),
         filled.size(),
         result.size(),
         canonical.size(),
         filled.size() - raw.size(),
         scanMissing.size(),
         denseScanMissing,
         removed.size(),
         carvedCanonical.size(),
         buriedOutline,
         componentSizes[0],
         componentSizes[1]
      );
   }

   static int solidNeighborCount(BlockPos position, Set<BlockPos> solid) {
      int result = 0;
      for (int[] direction : NEIGHBORS) {
         if (solid.contains(position.offset(direction[0], direction[1], direction[2]))) {
            result++;
         }
      }
      return result;
   }

   static boolean isBoundary(BlockPos position, Set<BlockPos> solid) {
      return solidNeighborCount(position, solid) < NEIGHBORS.length;
   }

   static int[] removedComponentSizes(Set<BlockPos> removed) {
      HashSet<BlockPos> remaining = new HashSet<>(removed);
      int components = 0;
      int largest = 0;
      while (!remaining.isEmpty()) {
         components++;
         int size = 0;
         ArrayDeque<BlockPos> queue = new ArrayDeque<>();
         queue.add(remaining.iterator().next());
         while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            if (!remaining.remove(current)) {
               continue;
            }
            size++;
            for (int[] direction : NEIGHBORS) {
               queue.add(current.offset(direction[0], direction[1], direction[2]));
            }
         }
         largest = Math.max(largest, size);
      }
      return new int[]{components, largest};
   }
}

void printKnown(String name, Vec3 edgeA, Vec3 edgeB, Vec3 extrusion) throws Exception {
   System.out.println(name + " " + TiltedBoxDiagnostics.analyze(edgeA, edgeB, extrusion));
}

printKnown("main", new Vec3(3, 3, 0), new Vec3(-1, 1, 4), new Vec3(6, -6, 3));
printKnown("bridges", new Vec3(4, 0, 4), new Vec3(-2, 4, 2), new Vec3(-4, -4, 4));
printKnown("cavity1", new Vec3(2, -1, -2), new Vec3(-1, 4, -3), new Vec3(4, 4, -4));
printKnown("cavity2", new Vec3(-1, 2, 1), new Vec3(2, 0, 2), new Vec3(-2, -1, 0));
printKnown("carve", new Vec3(-1, -2, -1), new Vec3(0, -2, -1), new Vec3(-1, 1, 2));

Random random = new Random(0xFA57F0L);
ArrayList<Vec3> vectors = new ArrayList<>();
for (int x = -3; x <= 3; x++) {
   for (int y = -3; y <= 3; y++) {
      for (int z = -3; z <= 3; z++) {
         if (x != 0 || y != 0 || z != 0) {
            vectors.add(new Vec3(x, y, z));
         }
      }
   }
}
int samples = 0;
int scanShapes = 0;
int denseScanShapes = 0;
int carveShapes = 0;
int bothShapes = 0;
int cavityShapes = 0;
long scanMissing = 0;
long denseScanMissing = 0;
long carved = 0;
long carvedCanonical = 0;
int largestCarve = 0;
while (samples < 5000) {
   Vec3 edgeA = vectors.get(random.nextInt(vectors.size()));
   Vec3 edgeB = vectors.get(random.nextInt(vectors.size()));
   Vec3 extrusion = vectors.get(random.nextInt(vectors.size()));
   if (edgeA.cross(edgeB).lengthSqr() < 1.0E-7 || Math.abs(edgeA.cross(edgeB).dot(extrusion)) < 1.0E-7) {
      continue;
   }
   TiltedBoxDiagnostics.Metrics metrics = TiltedBoxDiagnostics.analyze(edgeA, edgeB, extrusion);
   if (metrics == null) {
      continue;
   }
   samples++;
   scanShapes += metrics.scanMissing() > 0 ? 1 : 0;
   denseScanShapes += metrics.scanMissingWithThreeNeighbors() > 0 ? 1 : 0;
   carveShapes += metrics.carved() > 0 ? 1 : 0;
   bothShapes += metrics.scanMissing() > 0 && metrics.carved() > 0 ? 1 : 0;
   cavityShapes += metrics.filledCavities() > 0 ? 1 : 0;
   scanMissing += metrics.scanMissing();
   denseScanMissing += metrics.scanMissingWithThreeNeighbors();
   carved += metrics.carved();
   carvedCanonical += metrics.carvedCanonical();
   largestCarve = Math.max(largestCarve, metrics.largestRemovedComponent());
}
System.out.printf(
   "random samples=%d scanShapes=%d denseScanShapes=%d carveShapes=%d both=%d cavityShapes=%d scanMissing=%d denseScanMissing=%d carved=%d carvedCanonical=%d largestCarve=%d%n",
   samples, scanShapes, denseScanShapes, carveShapes, bothShapes, cavityShapes,
   scanMissing, denseScanMissing, carved, carvedCanonical, largestCarve
);
/exit
