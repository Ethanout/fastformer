package io.github.fastformer.server.submission;

import io.github.fastformer.common.workspace.submission.WorkspaceCompletion;
import net.minecraft.server.level.ServerPlayer;

/** Records the settled task result even when no player is online to receive it. */
@FunctionalInterface
public interface WorkspaceResultSink {
   void finish(WorkspaceCompletion result, ServerPlayer onlinePlayer);
}
