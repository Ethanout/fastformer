package io.github.fastformer.client;

import io.github.fastformer.client.input.ModifierReticleMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

/**
 * Applies the modifier reticle in screen space without replacing Minecraft's main framebuffer in place.
 */
final class SmoothReticlePostEffect {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ResourceLocation EFFECT = ResourceLocation.fromNamespaceAndPath(
      "fastformer", "shaders/post/reticle_invert.json"
   );
   private static final long RETRY_DELAY_NANOS = 2_000_000_000L;
   private static final long TRANSITION_DURATION_NANOS = 180_000_000L;
   private static final float VISUAL_SCALE = 1.08F;
   private static PostChain postChain;
   private static ResourceManager resourceManager;
   private static int width = -1;
   private static int height = -1;
   private static long nextRetryAt;
   private static boolean failureReported;
   private static ModifierReticleMode transitionFrom = ModifierReticleMode.NONE;
   private static ModifierReticleMode transitionTo = ModifierReticleMode.NONE;
   private static long transitionStartedAt;
   private static boolean transitionActive;

   private SmoothReticlePostEffect() {
   }

   static boolean prepare(Minecraft minecraft) {
      if (minecraft == null || minecraft.getMainRenderTarget() == null) {
         return false;
      }

      ResourceManager currentResources = minecraft.getResourceManager();
      if (postChain != null && resourceManager != currentResources) {
         close();
      }
      if (postChain != null) {
         return true;
      }
      if (System.nanoTime() < nextRetryAt) {
         return false;
      }

      try {
         postChain = new PostChain(
            minecraft.getTextureManager(),
            currentResources,
            minecraft.getMainRenderTarget(),
            EFFECT
         );
         resourceManager = currentResources;
         width = -1;
         height = -1;
         failureReported = false;
         return true;
      } catch (IOException | RuntimeException exception) {
         reportFailure(exception);
         close();
         nextRetryAt = System.nanoTime() + RETRY_DELAY_NANOS;
         return false;
      }
   }

   static boolean updateTarget(ModifierReticleMode mode) {
      ModifierReticleMode requested = mode == null
         ? ModifierReticleMode.NONE
         : mode;
      if (requested != transitionTo) {
         transitionFrom = transitionTo;
         transitionTo = requested;
         transitionStartedAt = System.nanoTime();
         transitionActive = transitionFrom != transitionTo;
      }
      finishTransitionIfReady();
      return needsRender();
   }

   static boolean process(Minecraft minecraft, float partialTicks) {
      if (!needsRender() || !prepare(minecraft)) {
         return false;
      }

      try {
         float transition = transitionProgress();
         finishTransitionIfReady();
         int targetWidth = minecraft.getWindow().getWidth();
         int targetHeight = minecraft.getWindow().getHeight();
         if (targetWidth != width || targetHeight != height) {
            postChain.resize(targetWidth, targetHeight);
            width = targetWidth;
            height = targetHeight;
         }

         float guiScale = (float)minecraft.getWindow().getGuiScale();
         float scale = guiScale * VISUAL_SCALE;
         postChain.setUniform("ReticleFromMode", modeValue(transitionFrom));
         postChain.setUniform("ReticleMode", modeValue(transitionTo));
         postChain.setUniform("ReticleTransition", transition);
         postChain.setUniform("ReticleCrossArm", 4.0F * scale);
         postChain.setUniform("ReticleCrossWidth", 1.0F * guiScale);
         postChain.setUniform("ReticleArmLength", 4.0F * scale);
         postChain.setUniform("ReticleArmWidth", 1.0F * guiScale);
         postChain.setUniform("ReticleArmGap", 2.5F * scale);
         postChain.setUniform("ReticleOffsetX", 0.0F);
         postChain.setUniform("ReticleOffsetY", 0.0F);
         postChain.setUniform("ReticlePointRingRadius", 3.5F * scale);
         postChain.setUniform("ReticlePointRingWidth", 1.0F * guiScale);
         postChain.setUniform("ReticlePointCenterSize", 1.0F * guiScale);
         postChain.setUniform("ReticleOpacity", 1.0F);

         RenderSystem.disableBlend();
         RenderSystem.disableDepthTest();
         RenderSystem.resetTextureMatrix();
         postChain.process(partialTicks);
         minecraft.getMainRenderTarget().bindWrite(false);
         return true;
      } catch (RuntimeException exception) {
         reportFailure(exception);
         close();
         nextRetryAt = System.nanoTime() + RETRY_DELAY_NANOS;
         return false;
      }
   }

   static void close() {
      if (postChain != null) {
         postChain.close();
      }
      postChain = null;
      resourceManager = null;
      width = -1;
      height = -1;
   }

   static void reset() {
      close();
      transitionFrom = ModifierReticleMode.NONE;
      transitionTo = ModifierReticleMode.NONE;
      transitionStartedAt = 0L;
      transitionActive = false;
   }

   private static boolean needsRender() {
      return transitionActive || transitionTo != ModifierReticleMode.NONE;
   }

   private static float transitionProgress() {
      if (!transitionActive) {
         return 1.0F;
      }
      long elapsed = Math.max(0L, System.nanoTime() - transitionStartedAt);
      return Math.min(1.0F, (float)elapsed / (float)TRANSITION_DURATION_NANOS);
   }

   private static void finishTransitionIfReady() {
      if (transitionActive && transitionProgress() >= 1.0F) {
         transitionActive = false;
         transitionFrom = transitionTo;
      }
   }

   private static float modeValue(ModifierReticleMode mode) {
      return switch (mode) {
         case NONE -> 0.0F;
         case EMBEDDED -> 1.0F;
         case HALF_GRID -> 2.0F;
      };
   }

   private static void reportFailure(Exception exception) {
      if (!failureReported) {
         LOGGER.warn("FastFormer smooth modifier reticle is unavailable; keeping the vanilla crosshair.", exception);
         failureReported = true;
      }
   }
}
