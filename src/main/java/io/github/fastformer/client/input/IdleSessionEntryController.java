package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.ClientBlockTinker;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
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
      if (session.distantEntryPressGuard.capture(action, button, now)) {
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
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
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
      BlockHitResult hit = LongRangeBlockRaycast.clipForIdleEntry(minecraft.player, alt).hit();
      boolean distant = LongRangeBlockRaycast.beyondInteractionRange(minecraft.player, hit);
      if (middleEntry && !alt && !distant) return false;
      if (alt) {
         session.idleEntry.clear();
         boolean started = enter(minecraft, session, hit, now, control, 0, button, middleEntry);
         if (started) {
            session.entryConsumedAlt = true;
            protectDistantEntry(session, distant, button, now);
         }
         return started;
      }
      BlockHitResult anchor = middleEntry ? null : session.idleEntry.secondPress(now, eye, view);
      if (anchor != null) {
         long token = session.idleEntry.token();
         boolean restore = session.idleEntry.tinker();
         session.idleEntry.clear();
         if (!restore) return enter(minecraft, session, anchor, now, control, token, button, true);
         boolean started = IdleSelectionEntryController.begin(minecraft, session, token, eye, view, control, now);
         if (started) {
            session.idleEntryButton = button;
            minecraft.options.keyUse.setDown(false);
         }
         return started;
      }
      if (!middleEntry && hit.getType() == HitResult.Type.BLOCK
         && ClientBlockTinker.consumes(minecraft.player, InteractionHand.MAIN_HAND, hit)
         && NetworkRegistry.hasChannel(minecraft.getConnection(), TinkerInteractionPayload.TYPE.id())) {
         long token = ++nextToken;
         var original = InteractionShapeSnapshot.capture(minecraft.level, hit.getBlockPos());
         var changes = io.github.fastformer.fastplace.interaction.BlockTinker.preview(minecraft.player, hit);
         var changed = InteractionShapeSnapshot.capture(io.github.fastformer.client.render.PreviewBlockOcclusion.level(minecraft.level, changes), hit.getBlockPos());
         session.idleEntry.rememberTinker(token, now, original, changed, eye, view, minecraft.level, minecraft.player.getMainHandItem());
         session.idleEntryButton = button;
         minecraft.options.keyUse.setDown(false);
         PacketDistributor.sendToServer(new TinkerInteractionPayload(token, hit, eye, view));
         return true;
      }
      if (distant) {
         session.idleEntry.clear();
         boolean started = enter(minecraft, session, hit, now, control, 0, button, middleEntry);
         if (started) protectDistantEntry(session, true, button, now);
         return started;
      }
      if (hit.getType() == HitResult.Type.BLOCK
         && NetworkRegistry.hasChannel(minecraft.getConnection(), IdleInteractionCapturePayload.TYPE.id())) {
         long token = ++nextToken;
         session.idleEntry.remember(token, now, InteractionShapeSnapshot.capture(minecraft.level, hit.getBlockPos()),
            eye, view, minecraft.level, minecraft.player.getMainHandItem());
         PacketDistributor.sendToServer(new IdleInteractionCapturePayload(token, hit, eye, view));
      } else session.idleEntry.clear();
      return false;
   }

   private static void protectDistantEntry(ClientInputSession session, boolean distant, int button, long now) {
      if (distant && button == MouseButtonInputSemantics.RIGHT_BUTTON) session.distantEntryPressGuard.arm(now);
   }

   private static boolean enter(Minecraft minecraft, ClientInputSession session, BlockHitResult hit,
      long now, boolean control, long token, int button, boolean embedded) {
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
      boolean started = PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         ? QuickShapeStartController.capture(minecraft, session, hit, token, button, embedded)
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
