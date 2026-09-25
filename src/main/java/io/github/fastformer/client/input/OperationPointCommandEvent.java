package io.github.fastformer.client.input;

sealed interface OperationPointCommandEvent permits OperationPointCommandEvent.Press {
   record Press(OperationPointCommandPress snapshot) implements OperationPointCommandEvent { }
}
