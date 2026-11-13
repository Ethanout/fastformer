package io.github.fastformer.client.input;

import io.github.fastformer.common.raycast.InteractionRaycastRules;

import io.github.fastformer.fastplace.geometry.raycast.*;
import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.*;

/** The displayed outline and its camera sample belong to physical press, including replay. */
record IdlePressSnapshot(BlockHitResult outline, InteractionShapeSnapshot shape, Vec3 eye, Vec3 view,
   BlockHitResult directHit, boolean throughFluids) {
   static IdlePressSnapshot capture(Minecraft minecraft, boolean alt) {
      float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
      Vec3 eye = minecraft.player.getEyePosition(partialTick);
      Vec3 view = minecraft.player.getViewVector(partialTick);
      BlockHitResult outline = minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
         ? new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside()) : null;
      InteractionShapeSnapshot shape = outline == null ? null : SessionEntryRaycast.snapshot(minecraft.player,
         outline.getBlockPos(), InteractionRaycastRules.VANILLA_OUTLINE);
      boolean throughFluids = InteractionRaycastRules.passesThroughFluids(minecraft.player, alt);
      BlockHitResult direct = SessionEntryRaycast.clip(minecraft.player, eye, view, new InteractionRaycastRules(alt, throughFluids));
      return new IdlePressSnapshot(outline, shape, eye, view, direct, throughFluids);
   }
}
