// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the pilot's Funnel off touches only what it turned on. It ran {@code tailscale funnel reset}, which
 * cleared every serve and Funnel the user had.
 */
class TailscaleFunnelServiceTest {

    @Test
    void off_names_only_the_https_port_the_pilot_serves() {
        assertEquals(List.of("serve", "--https=443", "off"), TailscaleFunnelService.OFF);
        assertFalse(TailscaleFunnelService.OFF.contains("reset"));
    }

    /** The CLI comes first, wherever it was found (on a Mac it may be inside Tailscale.app), then the arguments. */
    @Test
    void a_command_is_the_cli_then_its_arguments() {
        List<String> argv = TailscaleFunnelService.command(TailscaleFunnelService.OFF);
        assertEquals(TailscaleFunnelService.cli(), argv.get(0));
        assertEquals(TailscaleFunnelService.OFF, argv.subList(1, argv.size()));
        assertTrue(argv.get(0).endsWith("tailscale") || argv.get(0).equals(TailscaleFunnelService.MAC_APP_CLI));
    }

    /** Wording from tailscale 1.102 with nothing served on :443. */
    @Test
    void a_handler_already_gone_counts_as_turned_off() {
        assertTrue(TailscaleFunnelService.turnedOff(0, ""));
        assertTrue(TailscaleFunnelService.turnedOff(1, "error: failed to remove web serve: handler does not exist"));
        assertFalse(TailscaleFunnelService.turnedOff(1, "Access denied: serve config denied"));
        assertFalse(TailscaleFunnelService.turnedOff(-1, null));
    }
}
