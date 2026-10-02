package io.github.fastformer.fastplace.selection;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Picks occupied outline shapes. The envelope and the empty space between pieces have no hit surface. */
public final class SmartSelectionRaycast {
   private SmartSelectionRaycast() { }

   public static OperationGeometry.RayHit hit(Map<BlockPos, ClientBlockSnapshot> blocks, BlockGetter world,
      CollisionContext collision, Vec3 eye, Vec3 view, double reach) {
      Pick picked = pick(blocks, world, collision, eye, view, reach);
      return picked == null ? null : picked.hit();
   }

   public record Pick(BlockPos position, OperationGeometry.RayHit hit) { }

   /** Alt adds and removes grid cells, including explicitly selected air. */
   public static Pick pickCells(Set<BlockPos> positions, Vec3 eye, Vec3 view, double reach) {
      Pick closest = null;
      for (BlockPos position : positions) {
         var hit = OperationGeometry.raycast(new AABB(position), eye, view,
            closest == null ? reach : closest.hit().distance());
         if (hit != null && (closest == null || hit.distance() < closest.hit().distance()))
            closest = new Pick(position, hit);
      }
      return closest;
   }

   public static BlockPos adjacent(Pick pick) {
      Vec3 normal = pick.hit().normal();
      return pick.position().relative(Direction.getNearest(normal.x, normal.y, normal.z));
   }

   public static Pick pick(Map<BlockPos, ClientBlockSnapshot> blocks, BlockGetter world,
      CollisionContext collision, Vec3 eye, Vec3 view, double reach) {
      BlockGetter snapshot = new BlockGetter() {
         public BlockEntity getBlockEntity(BlockPos pos) { return null; }
         public BlockState getBlockState(BlockPos pos) {
            var block = blocks.get(pos);
            return block != null ? block.state() : world == null ? Blocks.AIR.defaultBlockState() : world.getBlockState(pos);
         }
         public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
         public int getHeight() { return world == null ? 384 : world.getHeight(); }
         public int getMinBuildHeight() { return world == null ? -64 : world.getMinBuildHeight(); }
      };
      Pick closest = null;
      for (var entry : blocks.entrySet()) {
         BlockPos position = entry.getKey();
         // Outline shapes such as fences can extend outside their cell.
         if (OperationGeometry.raycast(new AABB(position).inflate(0.5), eye, view, reach) == null) continue;
         var state = entry.getValue().state();
         var shape = state.isAir() ? net.minecraft.world.phys.shapes.Shapes.block() : state.getShape(snapshot, position, collision);
         for (AABB box : shape.toAabbs()) {
            var hit = OperationGeometry.raycast(box.move(position).inflate(0.002), eye, view,
               closest == null ? reach : closest.hit().distance());
            if (hit != null && (closest == null || hit.distance() < closest.hit().distance())) closest = new Pick(position, hit);
         }
      }
      return closest;
   }
}
