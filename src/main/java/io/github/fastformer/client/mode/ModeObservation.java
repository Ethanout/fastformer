package io.github.fastformer.client.mode;

/** One observation of live workflows; mode priority belongs to the registry. */
public record ModeObservation(
   boolean restoringTask, boolean placementTask, boolean operationSession, boolean workspaceHasParts,
   boolean selectionConfirmed, boolean geometrySession, boolean buildingSession,
   boolean quickReplaceSession, boolean fillSession
) {
   public static ModeObservation idle() {
      return new ModeObservation(false, false, false, false, false, false, false, false, false);
   }
}
