package io.github.fastformer.client.preview.frame;

import io.github.fastformer.client.preview.hud.HudModel;
import io.github.fastformer.common.mode.ModeId;
import java.util.Objects;
import java.util.Optional;

/** One published visual snapshot shared by the HUD, world pass and vanilla outline suppression. */
public record PreviewFrame(long id, AnimationPhase animation, long paletteRevision, ModifierReticleMode reticle,
   HudModel hud, Optional<ModeId> mode, Optional<PreviewLayer<?>> world, Optional<PreviewLayer<?>> afterLevel, boolean blueOutline) {
   public static final PreviewFrame EMPTY = new PreviewFrame(0, new AnimationPhase(0, 0, 1), 0,
      ModifierReticleMode.NONE, HudModel.EMPTY, Optional.empty(), Optional.empty(), Optional.empty(), false);

   public PreviewFrame {
      Objects.requireNonNull(animation, "animation");
      Objects.requireNonNull(reticle, "reticle");
      Objects.requireNonNull(hud, "hud");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(world, "world");
      Objects.requireNonNull(afterLevel, "afterLevel");
      if (world.isPresent() && mode.isEmpty()) throw new IllegalArgumentException("A preview layer requires a registered mode");
   }
}
