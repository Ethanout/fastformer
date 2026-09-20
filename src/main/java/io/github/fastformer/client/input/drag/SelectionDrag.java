package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;

public sealed interface SelectionDrag permits WorkspaceFaceDrag, WorkspaceGizmoDrag {
   SelectionDragCapture capture();
   ClientOperationWorkspace.EditToken editToken();
}
