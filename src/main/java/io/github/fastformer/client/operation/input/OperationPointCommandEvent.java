package io.github.fastformer.client.operation.input;

public sealed interface OperationPointCommandEvent permits OperationPointCommandEvent.Press {
   public record Press(OperationPointCommandPress snapshot) implements OperationPointCommandEvent { }
}
