package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Caches the generated shell mesh for one confirmed or pending block set. */
public final class BuildingShellCache {
   private final boolean pending;
   private BlockState state;
   private Map<net.minecraft.core.BlockPos, BlockState> stateOverrides = Map.of();
   private Set<net.minecraft.core.BlockPos> blocks = Set.of();
   private Set<net.minecraft.core.BlockPos> shapeEnvironment = Set.of();
   private Map<net.minecraft.core.BlockPos, BuildingSpecialBlock> specialStyles = Map.of();
   private boolean playerShift;
   private ShapeShellMesh.Mesh mesh = ShapeShellMesh.Mesh.empty();

   public BuildingShellCache(boolean pending) {
      this.pending = pending;
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
      if (this.state == state
         && this.blocks.equals(blocks)
         && this.stateOverrides.equals(stateOverrides)
         && this.shapeEnvironment.equals(shapeEnvironment)
         && this.specialStyles.equals(specialStyles)
         && this.playerShift == playerShift) {
         return this.mesh;
      }

      this.state = state;
      this.blocks = Set.copyOf(blocks);
      this.stateOverrides = Map.copyOf(stateOverrides);
      this.shapeEnvironment = Set.copyOf(shapeEnvironment);
      this.specialStyles = Map.copyOf(specialStyles);
      this.playerShift = playerShift;

      ArrayList<ShapeShellMesh.Part> parts = new ArrayList<>(blocks.size());
      for (net.minecraft.core.BlockPos pos : blocks) {
         BlockState stateAt = stateOverrides.getOrDefault(pos, state);
         List<AABB> boxes = stateAt == null
            ? List.of(new AABB(pos))
            : stateAt.getShape(previewLevel, pos, collision).toAabbs().stream()
               .map(box -> box.move(pos))
               .toList();
         if (boxes.isEmpty()) {
            continue;
         }

         BuildingSpecialBlock special = this.pending ? null : specialStyles.get(pos);
         ShapeShellMesh.Color faceColor = special == null
            ? ShapeShellMesh.Color.WHITE
            : new ShapeShellMesh.Color(special.style().red(), special.style().green(), special.style().blue());
         ShapeShellMesh.Color outlineColor = this.pending
            ? ShapeShellMesh.Color.WHITE
            : ShapeShellMesh.Color.BLACK;
         parts.add(new ShapeShellMesh.Part(boxes, faceColor, outlineColor, true));
      }
      this.mesh = ShapeShellMesh.build(parts);
      return this.mesh;
   }

   public void clear() {
      this.state = null;
      this.stateOverrides = Map.of();
      this.blocks = Set.of();
      this.shapeEnvironment = Set.of();
      this.specialStyles = Map.of();
      this.playerShift = false;
      this.mesh = ShapeShellMesh.Mesh.empty();
   }
}
