package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;

/** Allows ordered geometry actions from one published revision while that same session advances. */
public final class GeometryPointerSequence {
   private final GeometrySession session;
   private final java.util.UUID draftId;
   private final OperationCallbackScope scope;
   private final long capturedRevision;
   private long resultingRevision;

   public GeometryPointerSequence(GeometrySession session, OperationCallbackScope scope, long revision) {
      this.session = session;
      this.draftId = session.draftId();
      this.scope = scope;
      this.capturedRevision = revision;
      this.resultingRevision = revision;
   }

   public boolean accepts(GeometrySession currentSession, OperationCallbackScope currentScope,
      long inputRevision, long currentRevision) {
      return session == currentSession && draftId.equals(currentSession.draftId()) && scope.equals(currentScope)
         && capturedRevision == inputRevision && resultingRevision == currentRevision;
   }

   public void advance(long revision) {
      resultingRevision = revision;
   }
}
