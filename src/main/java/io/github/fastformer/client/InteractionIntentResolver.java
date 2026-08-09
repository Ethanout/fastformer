package io.github.fastformer.client;

import java.util.List;
import java.util.Optional;

/** Resolves the first component intent in explicit pointer-priority order. */
public final class InteractionIntentResolver {
   private InteractionIntentResolver() {
   }

   public static Optional<OperationInteractionIntent> resolve(
      InteractionContext context, List<InteractionIntentProvider> providers
   ) {
      if (context == null || providers == null) {
         return Optional.empty();
      }
      for (InteractionIntentProvider provider : providers) {
         if (provider == null) {
            continue;
         }
         Optional<OperationInteractionIntent> intent = provider.resolve(context);
         if (intent.isPresent()) {
            return intent;
         }
      }
      return Optional.empty();
   }
}
