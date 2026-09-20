package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;

import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Builds the geometric frame and applies axes selected by {@link SmartWoodFrameOrientation}. */
public final class SmartWoodFrame {
   private SmartWoodFrame() {
   }

   /** A continuous geometric edge. It does not contain a Minecraft wood axis. */
   public record Edge(BlockPos from, BlockPos to) {
      public Edge {
         from = from == null ? BlockPos.ZERO : from.immutable();
         to = to == null ? from : to.immutable();
      }

      Vec3 direction() {
         Vec3 vector = new Vec3(
            to.getX() - from.getX(),
            to.getY() - from.getY(),
            to.getZ() - from.getZ()
         );
         return vector.lengthSqr() < 1.0E-7 ? Vec3.ZERO : vector.normalize();
      }
   }

   public record Config(
      Direction.Axis baseAxis,
      List<BlockPos> guidePoints,
      List<Edge> edges,
      LineTieBias tieBias
   ) {
      Config(Direction.Axis baseAxis, List<BlockPos> guidePoints) {
         this(baseAxis, guidePoints, List.of(), LineTieBias.DEFAULT);
      }

      Config(Direction.Axis baseAxis, List<BlockPos> guidePoints, List<Edge> edges) {
         this(baseAxis, guidePoints, edges, LineTieBias.DEFAULT);
      }

      public Config {
         baseAxis = baseAxis == null ? Direction.Axis.Y : baseAxis;
         guidePoints = guidePoints == null ? List.of() : List.copyOf(guidePoints);
         edges = edges == null ? List.of() : List.copyOf(edges);
         tieBias = tieBias == null ? LineTieBias.DEFAULT : tieBias;
      }
   }

   public static Config config(
      Direction.Axis baseAxis,
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape volumeShape
   ) {
      LineTieBias edgeTieBias = points.size() >= 3 && modes.faceMode() != FaceMode.POLYGON
         ? modes.faceTieBias()
         : LineTieBias.DEFAULT;
      return new Config(
         baseAxis,
         points,
         edgeGuides(points, modes.faceMode(), polygonHeightConfirmed, volumeShape),
         edgeTieBias
      );
   }

   /** Builds the authored geometric edges used by the shape generator. */
   static List<Edge> edgeGuides(
      List<BlockPos> points,
      FaceMode faceMode,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape volumeShape
   ) {
      if (points == null || points.size() < 2) {
         return List.of();
      }
      if (points.size() == 2) {
         return List.of(new Edge(points.get(0), points.get(1)));
      }

      boolean volume = polygonHeightConfirmed && faceMode == FaceMode.POLYGON
         || faceMode != FaceMode.POLYGON && points.size() >= 4;
      List<BlockPos> base = faceMode == FaceMode.POLYGON
         ? points.subList(0, volume ? points.size() - 1 : points.size())
         : PlanarFaceGeometry.vertices(points, faceMode).stream().map(BlockPos::containing).toList();
      if (base.size() < 2) {
         return List.of();
      }

      java.util.ArrayList<Edge> result = new java.util.ArrayList<>();
      for (int index = 0; index < base.size(); index++) {
         result.add(new Edge(base.get(index), base.get((index + 1) % base.size())));
      }
      if (!volume) {
         return List.copyOf(result);
      }

      BlockPos anchor = points.get(Math.min(2, base.size() - 1));
      BlockPos tip = faceMode == FaceMode.POLYGON ? points.getLast() : points.get(3);
      BlockPos offset = tip.subtract(anchor);
      if (faceMode == FaceMode.POLYGON && volumeShape == PolygonVolumeShape.APEX) {
         List<Vec3> baseVectors = base.stream().map(Vec3::atCenterOf).toList();
         Vec3 normal = PlanarFaceGeometry.normal(baseVectors);
         Vec3 center = baseVectors.stream().reduce(Vec3.ZERO, Vec3::add)
            .scale(1.0 / baseVectors.size());
         Vec3 extrusion = Vec3.atCenterOf(tip).subtract(Vec3.atCenterOf(anchor));
         BlockPos apex = BlockPos.containing(center.add(normal.scale(extrusion.dot(normal))));
         for (BlockPos corner : base) {
            result.add(new Edge(corner, apex));
         }
      } else {
         for (Edge edge : List.copyOf(result)) {
            result.add(new Edge(edge.from().offset(offset), edge.to().offset(offset)));
         }
         for (BlockPos corner : base) {
            result.add(new Edge(corner, corner.offset(offset)));
         }
      }
      return List.copyOf(result);
   }

   public static Map<BlockPos, BlockState> resolve(Set<BlockPos> positions, BlockState prototype, Config config) {
      if (positions == null || positions.isEmpty() || config == null || prototype == null
         || !prototype.hasProperty(BlockStateProperties.AXIS)) {
         return Map.of();
      }
      Map<BlockPos, Direction.Axis> axes = SmartWoodFrameOrientation.resolve(positions, config);
      return new ResolvedStateMap(axes, prototype);
   }

   static Direction.Axis axisForTest(Set<BlockPos> positions, BlockPos position, Config config) {
      if (config == null || position == null) {
         return Direction.Axis.Y;
      }
      return SmartWoodFrameOrientation.resolve(positions, config)
         .getOrDefault(position, config.baseAxis());
   }

   /** Read-only state view that avoids copying one BlockState entry per edge voxel. */
   private static final class ResolvedStateMap extends AbstractMap<BlockPos, BlockState> {
      private final Map<BlockPos, Direction.Axis> axes;
      private final BlockState prototype;

      private ResolvedStateMap(Map<BlockPos, Direction.Axis> axes, BlockState prototype) {
         this.axes = Collections.unmodifiableMap(axes);
         this.prototype = prototype;
      }

      @Override
      public Set<Entry<BlockPos, BlockState>> entrySet() {
         return new AbstractSet<>() {
            @Override
            public int size() {
               return ResolvedStateMap.this.axes.size();
            }

            @Override
            public Iterator<Entry<BlockPos, BlockState>> iterator() {
               Iterator<Entry<BlockPos, Direction.Axis>> source =
                  ResolvedStateMap.this.axes.entrySet().iterator();
               return new Iterator<>() {
                  @Override
                  public boolean hasNext() {
                     return source.hasNext();
                  }

                  @Override
                  public Entry<BlockPos, BlockState> next() {
                     Entry<BlockPos, Direction.Axis> entry = source.next();
                     return new SimpleImmutableEntry<>(
                        entry.getKey(), ResolvedStateMap.this.prototype.setValue(
                           BlockStateProperties.AXIS, entry.getValue()
                        )
                     );
                  }
               };
            }
         };
      }

      @Override
      public BlockState get(Object key) {
         Direction.Axis axis = this.axes.get(key);
         return axis == null ? null : this.prototype.setValue(BlockStateProperties.AXIS, axis);
      }
   }
}
