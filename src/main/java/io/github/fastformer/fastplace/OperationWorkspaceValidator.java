package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Pure all-or-nothing validation/composition for an untrusted workspace plan. */
public final class OperationWorkspaceValidator {
   private OperationWorkspaceValidator() {
   }

   public static Result validate(OperationWorkspacePlan plan, LiveBlockLookup live, int maxBlocks) {
      if (plan == null || live == null || maxBlocks < 1 || plan.parts().isEmpty()
         || plan.parts().size() > ClientOperationWorkspace.MAX_PARTS) {
         return Result.failed(List.of());
      }
      List<OperationWorkspacePlan.Part> parts = new ArrayList<>(plan.parts());
      parts.sort(Comparator.comparingInt(OperationWorkspacePlan.Part::id));
      Set<Integer> ids = new HashSet<>();
      long supplied = 0L;
      long planned = 0L;
      for (OperationWorkspacePlan.Part part : parts) {
         if (part == null || part.id() < 1 || part.id() > ClientOperationWorkspace.MAX_PARTS
            || !ids.add(part.id()) || part.source() == null || !validTransform(part.transform()) || part.blocks().isEmpty()
            || (supplied += part.blocks().size()) > maxBlocks) {
            return Result.failed(List.of());
         }
         if (!part.pendingDelete()) {
            long cells = part.transform().repeats().cellCount();
            io.github.fastformer.client.operation.selection.OccupiedBlockBounds bounds =
               io.github.fastformer.client.operation.selection.OccupiedBlockBounds.from(part.blocks().keySet()).orElseThrow();
            long scaledX = Math.max(1L, Math.round(bounds.width(io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.X) * part.transform().scale().x));
            long scaledY = Math.max(1L, Math.round(bounds.width(io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.Y) * part.transform().scale().y));
            long scaledZ = Math.max(1L, Math.round(bounds.width(io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.Z) * part.transform().scale().z));
            long scaledVolume = saturatingMultiply(saturatingMultiply(scaledX, scaledY), scaledZ);
            if (cells <= 0L || scaledVolume > (long)maxBlocks / cells) {
               return Result.failed(List.of());
            }
            planned += scaledVolume * cells;
            if (planned > maxBlocks) {
               return Result.failed(List.of());
            }
         }
      }
      if (!WorldOperationMemory.snapshotAdmission(planned, 0L).fitsCurrentHeap()) {
         return Result.failed(List.of());
      }

      LinkedHashSet<BlockPos> clears = new LinkedHashSet<>();
      LinkedHashMap<BlockPos, ClientBlockSnapshot> writes = new LinkedHashMap<>();
      for (OperationWorkspacePlan.Part part : parts) {
         if (part.source() == ClientSelectionPart.Source.WORLD) {
            part.blocks().keySet().forEach(pos -> clears.add(pos.immutable()));
         }
      }
      for (OperationWorkspacePlan.Part part : parts) {
         if (part.pendingDelete()) {
            continue;
         }
         Map<BlockPos, ClientBlockSnapshot> resolved = WorkspacePreviewComposer.resolveValues(
            part.blocks(), part.transform()
         );
         for (Map.Entry<BlockPos, ClientBlockSnapshot> entry : resolved.entrySet()) {
            if (!entry.getValue().state().isAir()) {
               writes.put(entry.getKey().immutable(), entry.getValue());
            }
         }
         if (writes.size() > maxBlocks) {
            return Result.failed(List.of());
         }
      }
      return new Result(true, List.of(), Set.copyOf(clears), Map.copyOf(writes));
   }

   static boolean validTransform(WorkspaceTransform transform) {
      if (transform == null || !finite(transform.translation()) || !finite(transform.rotation()) || !finite(transform.scale())) {
         return false;
      }
      if (transform.scale().x < 1.0 / 1024.0 || transform.scale().y < 1.0 / 1024.0 || transform.scale().z < 1.0 / 1024.0
         || transform.scale().x > 1024.0 || transform.scale().y > 1024.0 || transform.scale().z > 1024.0) {
         return false;
      }
      if (Math.abs(transform.translation().x) > 30_000_000.0
         || Math.abs(transform.translation().y) > 30_000_000.0
         || Math.abs(transform.translation().z) > 30_000_000.0) {
         return false;
      }
      BlockPos min = transform.repeats().min();
      BlockPos max = transform.repeats().max();
      if (Math.abs(min.getX()) > OperationStackRegion.ENDPOINT_LIMIT
         || Math.abs(min.getY()) > OperationStackRegion.ENDPOINT_LIMIT
         || Math.abs(min.getZ()) > OperationStackRegion.ENDPOINT_LIMIT
         || Math.abs(max.getX()) > OperationStackRegion.ENDPOINT_LIMIT
         || Math.abs(max.getY()) > OperationStackRegion.ENDPOINT_LIMIT
         || Math.abs(max.getZ()) > OperationStackRegion.ENDPOINT_LIMIT) {
         return false;
      }
      BlockPos stride = transform.repeatStride();
      return Math.abs((long)stride.getX()) <= 16_000_000L
         && Math.abs((long)stride.getY()) <= 16_000_000L
         && Math.abs((long)stride.getZ()) <= 16_000_000L;
   }

   private static boolean finite(net.minecraft.world.phys.Vec3 value) {
      return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   private static long saturatingMultiply(long left, long right) {
      return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
   }

   @FunctionalInterface
   public interface LiveBlockLookup {
      Optional<ClientBlockSnapshot> read(BlockPos pos);
   }

   public record Result(
      boolean success,
      List<Integer> invalidPartIds,
      Set<BlockPos> clears,
      Map<BlockPos, ClientBlockSnapshot> writes
   ) {
      public Result {
         invalidPartIds = List.copyOf(invalidPartIds);
         clears = Set.copyOf(clears);
         writes = Map.copyOf(writes);
      }

      static Result failed(List<Integer> invalidPartIds) {
         return new Result(false, invalidPartIds, Set.of(), Map.of());
      }
   }
}
