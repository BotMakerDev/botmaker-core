package com.botmaker.core.run.agent.run;

import com.botmaker.core.run.claims.RunRegistry;
import com.botmaker.plugin.api.params.Claims;

/**
 * The {@link Claims} of one of several runs, checked against the live runs of this PC (spec
 * {@code 2026-11-15-run-claims-design.md} §6): a key another live run holds is refused, and every claim goes with
 * this run's process. A registry that cannot be read refuses the claim: granting it could put two runs on one key.
 */
final class RunClaims implements Claims.Service {

    private final RunRegistry registry;
    private final String runId;

    RunClaims(RunRegistry registry, String runId) {
        this.registry = registry;
        this.runId = runId;
    }

    @Override
    public boolean claim(String key) {
        try {
            return registry.claim(runId, key);
        } catch (RuntimeException e) {
            System.err.println("[BotMaker] the claim on " + key + " is refused: the live runs could not be read: " + e);
            return false;
        }
    }

    @Override
    public void release(String key) {
        try {
            registry.unclaim(runId, key);
        } catch (RuntimeException e) {
            // Freed anyway when this run's process ends.
            System.err.println("[BotMaker] " + key + " stays claimed until this run ends: " + e);
        }
    }
}
