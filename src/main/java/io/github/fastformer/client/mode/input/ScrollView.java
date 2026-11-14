package io.github.fastformer.client.mode.input;

/** Explicit facts needed by wheel handlers; no handler queries the current client. */
public record ScrollView(
   boolean workspaceActive, boolean usesScrollContext, boolean modifierYieldsToVanilla, boolean vanillaOwnsPointer
) {}
