package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.mode.ServerMode;
import io.github.fastformer.server.mode.ServerEditor;
import io.github.fastformer.common.mode.ModeId;

/** Connection-bound tools never park durable drafts in another dimension. */
public abstract class TemporaryServerMode<S> extends ServerMode<S> {
   protected TemporaryServerMode(ModeId id, ServerEditor<S> repository) {
      super(id, InteractionState.VANILLA, repository);
   }
}
