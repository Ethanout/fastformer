package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.client.operation.preview.Composition;

/** Whole-copy result. A refusal leaves the stored clipboard unchanged. */
public sealed interface ClipboardCopy {
   record Copied(OperationClipboard clipboard) implements ClipboardCopy {
   }

   record Empty() implements ClipboardCopy {
   }

   record TooLarge(Composition.Limit limit, long cap, long reached) implements ClipboardCopy {
   }
}
