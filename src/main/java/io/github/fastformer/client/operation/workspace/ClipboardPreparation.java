package io.github.fastformer.client.operation.workspace;

import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.client.operation.preview.Composition;
import java.util.List;

/** Whole-copy clipboard result. A refusal keeps the previous clipboard. */
public sealed interface ClipboardPreparation {
   record Copied(List<OperationClipboard.Part> parts) implements ClipboardPreparation {
      public Copied {
         parts = parts == null ? List.of() : List.copyOf(parts);
      }
   }

   record Empty() implements ClipboardPreparation {
   }

   record TooLarge(Composition.Limit limit, long cap, long reached) implements ClipboardPreparation {
   }
}
