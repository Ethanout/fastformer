/**
 * Per-server mode registration and common editing actions. Each mode owns its activity query,
 * ray policy and action dispatch; each editor repository is registered once for lifetime
 * boundaries, independently of how many shape modes share it. Connection-bound tools are
 * registered separately from parked editing drafts; vanilla input pass ends FF arbitration.
 */
package io.github.fastformer.server.mode;
