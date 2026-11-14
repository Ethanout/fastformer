package io.github.fastformer.common.geometry.raster.face.policy;

import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Carries captured generation rules without a global or thread-local current policy. */
public interface FacePolicyObserver extends BlockGenerationObserver {
   FaceRasterPolicySet facePolicies();

   static FaceRasterPolicySet policies(BlockGenerationObserver observer) {
      return observer instanceof FacePolicyObserver captured ? captured.facePolicies() : FaceRasterPolicySet.DEFAULT;
   }

   static FacePolicyObserver capture(FaceRasterPolicySet policies, BlockGenerationObserver delegate) {
      Objects.requireNonNull(policies, "policies");
      Objects.requireNonNull(delegate, "delegate");
      return new FacePolicyObserver() {
         @Override public FaceRasterPolicySet facePolicies() { return policies; }
         @Override public void onScanned(long amount) { delegate.onScanned(amount); }
         @Override public void onGenerated(BlockPos position) { delegate.onGenerated(position); }
         @Override public void checkCancelled() { delegate.checkCancelled(); }
      };
   }

   /** Staging observers keep the same rules while changing which progress callbacks are published. */
   static BlockGenerationObserver forward(BlockGenerationObserver source, BlockGenerationObserver delegate) {
      return capture(policies(source), delegate);
   }

   /** Intermediate faces must not publish blocks, but still belong to the same cancellable job. */
   static BlockGenerationObserver staging(BlockGenerationObserver source) {
      return forward(source, new BlockGenerationObserver() {
         @Override public void checkCancelled() { source.checkCancelled(); }
      });
   }
}
