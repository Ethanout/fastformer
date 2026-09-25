package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;

/** Allows ordered actions from one published revision while that same draft advances. */
public final class QuickShapePointerSequence {
   private final QuickShapeDraft draft;
   private final OperationCallbackScope scope;
   private final long capturedRevision;
   private long resultingRevision;
   private final QuickShapeCandidateContext candidateContext;

   public QuickShapePointerSequence(QuickShapeDraft draft, OperationCallbackScope scope, long revision,
      QuickShapeCandidateContext candidateContext) {
      this.draft = draft;
      this.scope = scope;
      this.capturedRevision = revision;
      this.resultingRevision = revision;
      this.candidateContext = candidateContext;
   }

   public boolean accepts(QuickShapeDraft currentDraft, OperationCallbackScope currentScope,
      long inputRevision, long currentRevision) {
      return draft == currentDraft && scope.equals(currentScope)
         && capturedRevision == inputRevision && resultingRevision == currentRevision;
   }

   public QuickShapeCandidateContext candidateContext() { return candidateContext; }

   public void advance(long revision) { resultingRevision = revision; }
}
