package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.ClientBlockTinker;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast;
import io.github.fastformer.fastplace.geometry.raycast.InteractionRaycastRules;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.network.payload.placement.IdleInteractionCapturePayload;
import io.github.fastformer.network.payload.placement.TinkerInteractionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** The only mouse entry into an idle selection or quick-shape session. */
final class IdleSessionEntryController {
   private static long nextToken;
   private IdleSessionEntryController() { }

   static boolean capture(Minecraft minecraft, ClientInputSession session, int action, int button,
      long now, boolean alt, boolean control) {
      if (session.entryPressGuard.capture(action, button, now)) {
         minecraft.options.keyUse.setDown(false);
         return true;
      }
      if (action == MouseButtonInputSemantics.RELEASE && button == session.idleEntryButton) {
         session.idleEntryButton = -1;
         session.releaseQuickShapeButton(button);
         var release = session.selectionPointer.release(session.idleEntrySelectionButton, now);
         if (release != null) session.postSelectionPointer(release);
         session.idleEntrySelectionButton = -1;
         return true;
      }
      if (minecraft.player == null) return false;
      boolean middleEntry = button == MouseButtonInputSemantics.MIDDLE_BUTTON
         && alt && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
      if (session.routing.state() != ClientInputStateMachine.State.IDLE
         || button != MouseButtonInputSemantics.RIGHT_BUTTON && !middleEntry
         || action != MouseButtonInputSemantics.PRESS || !minecraft.player.isCreative()
         || !FastPlaceClientPreview.enabled() || minecraft.getCameraEntity() != minecraft.player
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), IdleInteractionCapturePayload.TYPE.id())) return false;
      if (!alt && minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.ENTITY) {
         session.idleEntry.clear();
         return false;
      }
      session.idleEntry.validate(minecraft.level, minecraft.player.getMainHandItem());
      var eye = minecraft.player.getEyePosition();
      var view = minecraft.player.getViewVector(1);
      boolean throughFluids = InteractionRaycastRules.passesThroughFluids(minecraft.player, alt);
      BlockHitResult hit = SessionEntryRaycast.clip(minecraft.player, eye, view, new InteractionRaycastRules(alt, throughFluids));
      boolean distant = LongRangeBlockRaycast.beyondInteractionRange(minecraft.player, hit);
      if (alt) {
         session.idleEntry.clear();
         boolean started = enter(minecraft, session, hit, now, control, 0, button, true, throughFluids);
         if (started) {
            session.entryConsumedAlt = true;
            protectEntry(session, button, now);
         }
         return started;
      }
      var anchor = middleEntry ? null : session.idleEntry.secondPress(now, eye, view);
      if (anchor != null) {
         session.idleEntry.clear();
         if (!anchor.tinker()) return enter(minecraft, session, anchor.hit(), now, control, anchor.token(), button,
            anchor.rules().embedded(), anchor.rules().throughFluids());
         boolean started = IdleSelectionEntryController.begin(minecraft, session, anchor.token(), eye, view, control, now);
         if (started) {
            session.idleEntryButton = button;
            minecraft.options.keyUse.setDown(false);
         }
         return started;
      }
      BlockHitResult firstHit = minecraft.hitResult instanceof BlockHitResult blockHit
         && blockHit.getType() == HitResult.Type.BLOCK ? blockHit : null;
      float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(true);
      var firstEye = minecraft.player.getEyePosition(partialTick);
      var firstView = minecraft.player.getViewVector(partialTick);
      var rules = InteractionRaycastRules.VANILLA_OUTLINE;
      if (!middleEntry && firstHit != null
         && ClientBlockTinker.consumes(minecraft.player, InteractionHand.MAIN_HAND, firstHit)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), TinkerInteractionPayload.TYPE.id())) {
         long token = ++nextToken;
         var original = SessionEntryRaycast.snapshot(minecraft.player, firstHit.getBlockPos(), rules);
         session.idleEntry.rememberTinker(token, now, original, firstEye, firstView, minecraft.level, minecraft.player.getMainHandItem());
         session.idleEntryButton = button;
         minecraft.options.keyUse.setDown(false);
         PacketDistributor.sendToServer(new TinkerInteractionPayload(token, firstHit, firstEye, firstView));
         return true;
      }
      if (firstHit == null && distant) {
         session.idleEntry.clear();
         boolean started = enter(minecraft, session, hit, now, control, 0, button, middleEntry, throughFluids);
         if (started) protectEntry(session, button, now);
         return started;
      }
      // Freeze the displayed black-outline hit, including its face and position; do not recast it.
      if (firstHit != null) {
         long token = ++nextToken;
         session.idleEntry.remember(token, now, SessionEntryRaycast.snapshot(minecraft.player, firstHit.getBlockPos(), rules),
            firstEye, firstView, minecraft.level, minecraft.player.getMainHandItem(), rules);
         PacketDistributor.sendToServer(new IdleInteractionCapturePayload(token, firstHit, firstEye, firstView, rules));
      } else session.idleEntry.clear();
      return false;
   }

   private static void protectEntry(ClientInputSession session, int button, long now) {
      if (button == MouseButtonInputSemantics.RIGHT_BUTTON) session.entryPressGuard.arm(now);
   }

   private static boolean enter(Minecraft minecraft, ClientInputSession session, BlockHitResult hit,
      long now, boolean control, long token, int button, boolean embedded, boolean throughFluids) {
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
      boolean started = PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         ? QuickShapeStartController.capture(minecraft, session, hit, token, button, embedded, throughFluids)
         : FastPlaceClientInput.captureSelectionEntry(hit.getBlockPos(), control, now);
      if (!started) return false;
      if (!PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())) {
         session.idleEntrySelectionButton = MouseButtonInputSemantics.RIGHT_BUTTON;
      }
      session.modifier.consume();
      session.idleEntryButton = button;
      minecraft.options.keyUse.setDown(false);
      return true;
   }
}
