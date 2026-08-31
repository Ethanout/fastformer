package io.github.fastformer.client.render.mesh;

import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.model.FaceCell;
import io.github.fastformer.client.render.model.GhostEdge;
import io.github.fastformer.client.render.model.GhostEdgeLine;
import io.github.fastformer.client.render.model.GhostInterval;
import io.github.fastformer.client.render.model.GhostMesh;
import io.github.fastformer.client.render.model.GhostPlane;
import io.github.fastformer.client.render.model.GhostQuad;
import io.github.fastformer.client.render.model.GridPoint;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Builds render-ready boundary meshes from voxel positions. */
public final class GhostMeshBuilder {
   private GhostMeshBuilder() {
   }

   /**
    * Builds visible faces and boundary edges for a voxel set.
    * Shared edges are cancelled per oriented face plane so geometric creases remain visible.
    */
   public static GhostMesh build(
      Set<BlockPos> blocks, boolean includeFaces, boolean cancelCoplanarSharedEdges, boolean mergeEdges
   ) {
      Map<GhostPlane, Set<FaceCell>> planes = includeFaces ? new HashMap<>() : null;
      Map<GhostPlane, Set<GhostEdge>> planeBoundaryEdges = new HashMap<>();
      for (BlockPos pos : blocks) {
         for (Direction direction : Direction.values()) {
            if (blocks.contains(pos.relative(direction))) {
               continue;
            }
            GhostPlane plane = GhostPlane.of(pos, direction);
            if (planes != null) {
               planes.computeIfAbsent(plane, ignored -> new HashSet<>()).add(FaceCell.of(pos, direction));
            }
            collectSurfaceEdges(
               planeBoundaryEdges.computeIfAbsent(plane, ignored -> new HashSet<>()),
               pos,
               direction,
               cancelCoplanarSharedEdges
            );
         }
      }

      ArrayList<GhostQuad> faces = new ArrayList<>();
      if (planes != null) {
         for (Map.Entry<GhostPlane, Set<FaceCell>> entry : planes.entrySet()) {
            mergeGhostPlane(entry.getKey(), entry.getValue(), faces);
         }
      }
      HashSet<GhostEdge> boundaryEdges = new HashSet<>();
      for (Set<GhostEdge> edges : planeBoundaryEdges.values()) {
         boundaryEdges.addAll(edges);
      }
      List<GhostEdge> edges = mergeEdges ? mergeCollinearEdges(boundaryEdges) : List.copyOf(boundaryEdges);
      return new GhostMesh(List.copyOf(faces), edges);
   }

   public static PendingGhostMesh buildPending(Set<BlockPos> blocks) {
      return new PendingGhostMesh(PendingPreviewGrid.build(blocks));
   }

   private static void mergeGhostPlane(GhostPlane plane, Set<FaceCell> cells, List<GhostQuad> output) {
      ArrayList<FaceCell> ordered = new ArrayList<>(cells);
      ordered.sort(Comparator.comparingInt(FaceCell::v).thenComparingInt(FaceCell::u));
      HashSet<FaceCell> remaining = new HashSet<>(cells);
      for (FaceCell start : ordered) {
         if (!remaining.remove(start)) {
            continue;
         }
         int width = 1;
         while (remaining.contains(new FaceCell(start.u() + width, start.v()))) {
            width++;
         }
         for (int u = 1; u < width; u++) {
            remaining.remove(new FaceCell(start.u() + u, start.v()));
         }

         int height = 1;
         while (containsGhostRow(remaining, start.u(), start.v() + height, width)) {
            for (int u = 0; u < width; u++) {
               remaining.remove(new FaceCell(start.u() + u, start.v() + height));
            }
            height++;
         }
         output.add(GhostQuad.of(plane, start.u(), start.v(), width, height));
      }
   }

   private static boolean containsGhostRow(Set<FaceCell> cells, int startU, int v, int width) {
      for (int u = 0; u < width; u++) {
         if (!cells.contains(new FaceCell(startU + u, v))) {
            return false;
         }
      }
      return true;
   }

   private static void collectSurfaceEdges(
      Set<GhostEdge> boundaryEdges, BlockPos pos, Direction direction, boolean cancelSharedEdges
   ) {
      for (GhostEdge edge : surfaceEdges(pos, direction)) {
         if (!boundaryEdges.add(edge) && cancelSharedEdges) {
            boundaryEdges.remove(edge);
         }
      }
   }

   private static GhostEdge[] surfaceEdges(BlockPos pos, Direction direction) {
      int x0 = pos.getX();
      int y0 = pos.getY();
      int z0 = pos.getZ();
      int x1 = x0 + 1;
      int y1 = y0 + 1;
      int z1 = z0 + 1;
      GridPoint a;
      GridPoint b;
      GridPoint c;
      GridPoint d;
      switch (direction) {
         case DOWN -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x1, y0, z0);
            c = new GridPoint(x1, y0, z1); d = new GridPoint(x0, y0, z1);
         }
         case UP -> {
            a = new GridPoint(x0, y1, z0); b = new GridPoint(x0, y1, z1);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x1, y1, z0);
         }
         case NORTH -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x0, y1, z0);
            c = new GridPoint(x1, y1, z0); d = new GridPoint(x1, y0, z0);
         }
         case SOUTH -> {
            a = new GridPoint(x0, y0, z1); b = new GridPoint(x1, y0, z1);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x0, y1, z1);
         }
         case WEST -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x0, y0, z1);
            c = new GridPoint(x0, y1, z1); d = new GridPoint(x0, y1, z0);
         }
         case EAST -> {
            a = new GridPoint(x1, y0, z0); b = new GridPoint(x1, y1, z0);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x1, y0, z1);
         }
         default -> throw new IllegalStateException("Unknown direction " + direction);
      }
      return new GhostEdge[]{GhostEdge.of(a, b), GhostEdge.of(b, c), GhostEdge.of(c, d), GhostEdge.of(d, a)};
   }

   private static List<GhostEdge> mergeCollinearEdges(Set<GhostEdge> edges) {
      Map<GhostEdgeLine, List<GhostInterval>> lines = new HashMap<>();
      for (GhostEdge edge : edges) {
         GhostEdgeLine line = GhostEdgeLine.of(edge);
         lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(line.interval(edge));
      }

      ArrayList<GhostEdge> merged = new ArrayList<>();
      for (Map.Entry<GhostEdgeLine, List<GhostInterval>> entry : lines.entrySet()) {
         List<GhostInterval> intervals = entry.getValue();
         intervals.sort(Comparator.comparingInt(GhostInterval::start));
         int start = intervals.getFirst().start();
         int end = intervals.getFirst().end();
         for (int i = 1; i < intervals.size(); i++) {
            GhostInterval next = intervals.get(i);
            if (next.start() <= end) {
               end = Math.max(end, next.end());
            } else {
               merged.add(entry.getKey().edge(start, end));
               start = next.start();
               end = next.end();
            }
         }
         merged.add(entry.getKey().edge(start, end));
      }
      return List.copyOf(merged);
   }
}
