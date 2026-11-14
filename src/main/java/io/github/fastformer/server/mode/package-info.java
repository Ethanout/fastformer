/**
 * Per-server mode registration and common editing actions. Each mode owns its activity query,
 * ray policy and action dispatch; each editor repository is registered once for lifetime
 * boundaries, independently of how many shape modes share it.
 */
package io.github.fastformer.server.mode;
