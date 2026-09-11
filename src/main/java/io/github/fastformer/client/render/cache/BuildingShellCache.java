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
   private ShapeShellMesh.Builder building;

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
      Set<net.minecraft.core.BlockPos> nextBlocks = Set.copyOf(blocks);
      Map<net.minecraft.core.BlockPos, BlockState> nextOverrides = Map.copyOf(stateOverrides);
      Set<net.minecraft.core.BlockPos> nextEnvironment = Set.copyOf(shapeEnvironment);
      Map<net.minecraft.core.BlockPos, BuildingSpecialBlock> nextStyles = Map.copyOf(specialStyles);

      boolean sameInput = this.state == state
         && this.blocks.equals(nextBlocks)
         && this.stateOverrides.equals(nextOverrides)
         && this.shapeEnvironment.equals(nextEnvironment)
         && this.specialStyles.equals(nextStyles)
         && this.playerShift == playerShift;
      if (sameInput) {
         if (this.building == null) {
            return this.mesh;
         }
         if (!this.building.step(4096)) {
            return this.mesh;
         }
         ShapeShellMesh.Mesh completed = this.building.mesh();
         this.mesh = completed;
         this.building = null;
         return completed;
      }

      // A changed snapshot invalidates any partially built mesh. It must never
      // publish a result derived from the previous input.
      this.building = null;

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
         parts.add(new ShapeShellMesh.Part(boxes, faceColor, outlineColor, true));
      }
      ShapeShellMesh.Builder nextBuilder = ShapeShellMesh.builder(parts);
      if (!nextBuilder.step(4096)) {
         this.building = nextBuilder;
         this.state = state;
         this.blocks = nextBlocks;
         this.stateOverrides = nextOverrides;
         this.shapeEnvironment = nextEnvironment;
         this.specialStyles = nextStyles;
         this.playerShift = playerShift;
         return this.mesh;
      }
      ShapeShellMesh.Mesh nextMesh = nextBuilder.mesh();
      this.state = state;
      this.blocks = nextBlocks;
      this.stateOverrides = nextOverrides;
      this.shapeEnvironment = nextEnvironment;
      this.specialStyles = nextStyles;
      this.playerShift = playerShift;
      this.mesh = nextMesh;
      this.building = null;
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
      this.building = null;
   }
}
