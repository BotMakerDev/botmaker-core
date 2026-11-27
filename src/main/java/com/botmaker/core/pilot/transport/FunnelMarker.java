// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot.transport;

/**
 * Where the process that has the pilot's Funnel up is recorded, so a Funnel outliving it can be turned off later
 * ({@link FunnelTransport#clearLeftover}). Best-effort: neither method throws.
 */
public interface FunnelMarker {

    /** The recorded process, or {@code 0} when none is. */
    long funnelOwner();

    /** Records the process that has the Funnel up; {@code 0} when it was turned off. */
    void funnelOwner(long pid);
}
