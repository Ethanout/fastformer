package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Geometry inputs belonging to one published draft revision. */
public record QuickShapeCandidateContext(List<BlockPos> points, boolean polygonClosed,
   Vec3 faceOffset, Vec3 volumeOffset, BlockPos perpendicularAnchor,
   BlockPos scrollOffset, FastPlaceGeometry.Modes modes, boolean modifierHeld) {
   public QuickShapeCandidateContext {
      points = points.stream().map(BlockPos::immutable).toList();
      perpendicularAnchor = perpendicularAnchor == null ? null : perpendicularAnchor.immutable();
      scrollOffset = scrollOffset.immutable();
   }

   public BlockPos resolve(BlockHitResult hit, Vec3 eye, Vec3 view) {
      BlockPos anchor = points.getFirst().offset(scrollOffset);
      boolean blockHit = hit.getType() == HitResult.Type.BLOCK;
      return FastPlaceGeometry.resolveCandidate(points, polygonClosed,
         blockHit ? hit.getBlockPos() : anchor,
         blockHit ? hit.getBlockPos().relative(hit.getDirection()) : anchor,
         faceOffset, volumeOffset, perpendicularAnchor, eye, view, scrollOffset, modes);
   }
}
