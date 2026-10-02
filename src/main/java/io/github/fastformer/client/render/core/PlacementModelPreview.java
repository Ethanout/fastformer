package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.PreviewMaterialRenderer;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Caches model eligibility separately from camera and opacity changes. */
final class PlacementModelPreview {
   private Set<BlockPos> positions;
   private Map<BlockPos, BlockState> overrides;
   private BlockState state;
   private Object models;
   private Map<BlockPos, ClientBlockSnapshot> snapshots = Map.of();
   private Map<BlockPos, BlockState> states = Map.of();
   private Set<BlockPos> fallback = Set.of();

   Set<BlockPos> render(PoseStack pose, BufferSource buffers, Vec3 camera, Set<BlockPos> positions,
      BlockState state, Map<BlockPos, BlockState> overrides, float alpha, boolean dynamic) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null || positions.isEmpty()) return Set.of();
      if (this.positions != positions || this.state != state || this.overrides != overrides
         || this.models != minecraft.getModelManager().getMissingModel()) {
         Map<BlockPos, ClientBlockSnapshot> snapshots = new HashMap<>();
         Set<BlockPos> fallback = new HashSet<>();
         for (BlockPos pos : positions) {
            BlockState at = overrides.getOrDefault(pos, state);
            if (at == null || at.getRenderShape() != RenderShape.MODEL
               || minecraft.getBlockRenderer().getBlockModel(at) == minecraft.getModelManager().getMissingModel()) {
               fallback.add(pos);
            } else snapshots.put(pos, new ClientBlockSnapshot(at, null));
         }
         this.positions = positions;
         this.state = state;
         this.overrides = overrides;
         this.models = minecraft.getModelManager().getMissingModel();
         this.snapshots = Map.copyOf(snapshots);
         Map<BlockPos, BlockState> states = new HashMap<>();
         snapshots.forEach((pos, snapshot) -> states.put(pos, snapshot.state()));
         this.states = Map.copyOf(states);
         this.fallback = Set.copyOf(fallback);
      }
      PreviewMaterialRenderer.draw(pose, buffers, minecraft, camera, snapshots,
         PreviewBlockOcclusion.level(minecraft.level, states), 1, 1, 1, alpha, dynamic);
      return fallback;
   }

   void clear() {
      positions = null;
      overrides = null;
      state = null;
      models = null;
      snapshots = Map.of();
      states = Map.of();
      fallback = Set.of();
   }
}
