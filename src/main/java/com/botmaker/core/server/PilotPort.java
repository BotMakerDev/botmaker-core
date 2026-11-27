// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.server;

import com.botmaker.core.pilot.PilotState;
import com.botmaker.core.pilot.transport.TransportKind;

/** What {@code POST /pilot} asks of the Remote Pilot ({@link CoreServer.Hooks#pilot}). */
public interface PilotPort {

    /** The pilot as it is now. */
    PilotState pilotState();

    /**
     * Brings it up over {@code transport} (the remembered way when null) and waits until it settled.
     *
     * @throws IllegalArgumentException with why it cannot start, for instance when no project is open
     */
    PilotState startPilot(TransportKind transport);

    /** Takes it down. */
    PilotState stopPilot();

    /** Revokes the pairing token; every phone has to scan again. */
    PilotState resetPilot();

    /** A server with no core behind it (a test's): nothing to pilot. */
    PilotPort NONE = new PilotPort() {
        @Override
        public PilotState pilotState() {
            throw new IllegalArgumentException("This core has no Remote Pilot.");
        }

        @Override
        public PilotState startPilot(TransportKind transport) {
            return pilotState();
        }

        @Override
        public PilotState stopPilot() {
            return pilotState();
        }

        @Override
        public PilotState resetPilot() {
            return pilotState();
        }
    };
}
