package io.github.fastformer.client.preview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.render.hud.HudFrameRenderer;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.input.HistoryConflictConfirmation;
import io.github.fastformer.client.render.HistoryConflictRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent.Post;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import io.github.fastformer.client.render.hud.BottomHudLayout;

/** Adapts render events to already-captured preview models. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientPreviewEvents {
   private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");
   private static boolean smoothReticleFrame;
   private ClientPreviewEvents() {}
   @SubscribeEvent
   public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
      if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
         return;
      }

      smoothReticleFrame = false;
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen != null || minecraft.player == null) {
         return;
      }
      boolean reticleNeeded = SmoothReticlePostEffect.frameNeedsRender();
      if (!reticleNeeded) {
         return;
      }

      // NeoForge exposes the vanilla crosshair as a cancellable GUI layer. This
      // keeps the post effect from inverting a second, pixelated crosshair.
      smoothReticleFrame = SmoothReticlePostEffect.prepare(minecraft);
      if (smoothReticleFrame) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onRenderGui(Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      GuiGraphics graphics = event.getGuiGraphics();
      if (!ClientPreviewSystem.instance().frame().hud().visible()) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }
      graphics.setColor(1, 1, 1, ClientPreviewSystem.instance().frame().hud().opacity());
      try {
         var bottom = BottomHudLayout.forFrame(graphics, minecraft);
         HudFrameRenderer.draw(graphics, minecraft.font, bottom, ClientPreviewSystem.instance().frame().hud());
         ClientPreviewSystem.instance().selection().drawHud(graphics, minecraft.font, bottom);
         graphics.flush();
         RenderSystem.setShaderColor(1, 1, 1, 1);
         if (smoothReticleFrame && !SmoothReticlePostEffect.process(minecraft, event.getPartialTick().getGameTimeDeltaTicks())) {
            restoreVanillaCrosshairIfNeeded(graphics);
         }
         smoothReticleFrame = false;
      } finally {
         restoreVanillaCrosshairIfNeeded(graphics);
         graphics.flush();
         graphics.setColor(1, 1, 1, 1);
         RenderSystem.setShaderColor(1, 1, 1, 1);
         smoothReticleFrame = false;
      }
   }

   private static void restoreVanillaCrosshairIfNeeded(GuiGraphics graphics) {
      if (smoothReticleFrame) {
         smoothReticleFrame = false;
         renderVanillaCrosshair(graphics);
      }
   }

   private static void renderVanillaCrosshair(GuiGraphics graphics) {
      RenderSystem.enableBlend();
      RenderSystem.blendFuncSeparate(
         GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
         GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
         GlStateManager.SourceFactor.ONE,
         GlStateManager.DestFactor.ZERO
      );
      int x = (graphics.guiWidth() - 15) / 2;
      int y = (graphics.guiHeight() - 15) / 2;
      graphics.blitSprite(CROSSHAIR_SPRITE, x, y, 15, 15);
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableBlend();
   }

   @SubscribeEvent
   public static void onRenderBlockHighlight(RenderHighlightEvent.Block event) {
      if (ClientPreviewSystem.instance().frame().blueOutline()) event.setCanceled(true);
   }

   @SubscribeEvent
   public static void onRenderLevelStage(RenderLevelStageEvent event) {
      var minecraft = Minecraft.getInstance();
      var system = ClientPreviewSystem.instance();
      if (event.getStage() == Stage.AFTER_LEVEL) {
         system.frame().afterLevel().ifPresent(layer -> layer.drawAfterLevel(worldFrame(event, minecraft)));
      } else if (event.getStage() == Stage.AFTER_PARTICLES) {
         HistoryConflictRenderer.render(event, minecraft, HistoryConflictConfirmation.pending(), HistoryConflictConfirmation.positions());
         system.frame().world().ifPresent(layer -> layer.draw(worldFrame(event, minecraft)));
      }
   }

   private static WorldPreviewFrame worldFrame(RenderLevelStageEvent event, Minecraft minecraft) {
      var frame = ClientPreviewSystem.instance().frame();
      return new WorldPreviewFrame(minecraft, event.getPoseStack(), minecraft.renderBuffers().bufferSource(),
         event.getCamera().getPosition(), 1, frame.animation(), frame.paletteRevision(), event.getModelViewMatrix(), event.getProjectionMatrix());
   }

   public static void clearWorldRenderState() {
      var sessions = ClientSessionSystem.instance();
      sessions.inputSession().clearGeometryPointer();
      sessions.inputSession().feedback().faces().clear();
      ClientPreviewSystem.instance().clear();
      SmoothReticlePostEffect.reset();
      smoothReticleFrame = false;
   }
}
