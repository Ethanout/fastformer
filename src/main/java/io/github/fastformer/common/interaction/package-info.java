/**
 * Shared interaction classification consumed by client observation and server mode routing.
 * The public state vocabulary is independent of either runtime; binding profiles are owned
 * by the binding subpackage. New state rules belong here only when both runtimes share them.
 *
 * @architecture-api-entry The shared classification enum is the complete cross-runtime
 * public vocabulary; implementation-specific policies remain in their owning mode packages.
 */
package io.github.fastformer.common.interaction;
