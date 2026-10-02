package io.github.fastformer.fastplace.selection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Connected components describe pieces without splitting selection ownership. */
public record SmartSelectionTopology(List<AABB> pieces, AABB bounds, Map<BlockPos, Set<BlockPos>> membersByPosition) {
   public SmartSelectionTopology {
      pieces = List.copyOf(pieces);
      membersByPosition = Map.copyOf(membersByPosition);
   }
   public boolean disconnected() { return pieces.size() > 1; }
   public double span() {
      if (bounds == null) return 0;
      return Math.sqrt(Math.pow(Math.max(0, bounds.getXsize() - 1), 2)
         + Math.pow(Math.max(0, bounds.getYsize() - 1), 2) + Math.pow(Math.max(0, bounds.getZsize() - 1), 2));
   }
   public boolean distant() { return disconnected() && span() > 64.0; }

   public Set<BlockPos> membersAt(BlockPos position) { return membersByPosition.getOrDefault(position, Set.of()); }

   public static SmartSelectionTopology of(Set<BlockPos> positions) {
      Set<BlockPos> remaining = new HashSet<>(positions);
      List<AABB> pieces = new ArrayList<>();
      Map<BlockPos, Set<BlockPos>> membersByPosition = new HashMap<>();
      AABB whole = null;
      ArrayDeque<BlockPos> queue = new ArrayDeque<>();
      while (!remaining.isEmpty()) {
         BlockPos seed = remaining.iterator().next();
         remaining.remove(seed);
         queue.add(seed);
         AABB piece = new AABB(seed);
         Set<BlockPos> members = new HashSet<>();
         while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst();
            members.add(position);
            piece = piece.minmax(new AABB(position));
            for (Direction direction : Direction.values()) {
               BlockPos neighbor = position.relative(direction);
               if (remaining.remove(neighbor)) queue.addLast(neighbor);
            }
         }
         pieces.add(piece);
         Set<BlockPos> immutableMembers = Set.copyOf(members);
         for (BlockPos position : immutableMembers) membersByPosition.put(position, immutableMembers);
         whole = whole == null ? piece : whole.minmax(piece);
      }
      pieces.sort(java.util.Comparator.comparingDouble((AABB box) -> box.minX)
         .thenComparingDouble(box -> box.minY).thenComparingDouble(box -> box.minZ));
      return new SmartSelectionTopology(pieces, whole, membersByPosition);
   }
}
