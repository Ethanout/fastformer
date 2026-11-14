package io.github.fastformer.client.preview.assembly;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.idle.IdlePreviewInput;
import io.github.fastformer.client.preview.frame.PreviewAnimation;
import io.github.fastformer.client.preview.block.BlockPreviewInput;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.common.world.BlockSnapshot;
import java.util.Map;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.client.render.theme.VisualThemes;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Captures visual time and pointer facts before the HUD and world draw the frame. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientPreviewFrameSystem {
   private ClientPreviewFrameSystem() {}

   @SubscribeEvent
   public static void beginFrame(RenderFrameEvent.Pre event) {
      PreviewAnimation.advance(System.nanoTime(), VisualThemes.value("boil_fps", 8),
         (int)VisualThemes.value("boil_sheets", 3));
      Minecraft minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var operations = sessions.operations();
      sessions.inputSession().pointer().invalidate();
      boolean visible = minecraft.player != null && minecraft.level != null
         && minecraft.screen == null && minecraft.isWindowActive();
      var pointer = operations.pointer().captureFrame(visible ? InteractionContext.capture(minecraft) : null);
      operations.updateVisualHover(operations.active() ? pointer.target().orElse(null) : null);
      ClientPreviewSystem.instance().captureIdleFrame(idleInput(minecraft, sessions));
      var replacement = QuickReplaceMode.preview(minecraft);
      var replacementInput = replacement == null ? BlockPreviewInput.EMPTY
         : new BlockPreviewInput(Map.of(replacement.position(), new BlockSnapshot(replacement.state(), null)));
      ClientPreviewSystem.instance().captureToolFrames(
         new BlockPreviewInput(sessions.inputSession().smartFill().blocks()), replacementInput);
   }

   private static IdlePreviewInput idleInput(Minecraft minecraft, ClientSessionSystem sessions) {
      var player = minecraft.player;
      boolean modifier = PointerDragSnapshotView.modifierHeld(sessions.inputSession());
      if (!sessions.modeView().idleActionsAllowed() || player == null || minecraft.level == null
         || !player.isCreative() || !sessions.previews().building().enabled()
         || minecraft.screen != null || minecraft.getCameraEntity() != player
         || !modifier && minecraft.hitResult != null
            && minecraft.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) return null;
      boolean placeable = PlaceableItems.isPlaceable(player.getMainHandItem());
      var hit = sessions.inputSession().pointer().selection(player, modifier).hit();
      boolean embedded = modifier && placeable;
      return embedded || LongRangeBlockRaycast.beyondInteractionRange(player, hit)
         ? new IdlePreviewInput(player, hit, embedded, InteractionRaycastRules.passesThroughFluids(player, modifier), placeable)
         : null;
   }
}
