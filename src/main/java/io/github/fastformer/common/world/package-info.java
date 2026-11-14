/**
 * Block contents shared by world operations, previews and persistence. Runtime capture,
 * transformation and writes remain in their owning capabilities.
 * @architecture-api-entry BlockSnapshot is the shared value contract, with defensive
 * NBT copies at construction and access so callers cannot mutate stored contents.
 */
package io.github.fastformer.common.world;
