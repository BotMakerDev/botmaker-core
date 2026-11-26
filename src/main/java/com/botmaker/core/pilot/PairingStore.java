// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.core.pilot;

/**
 * Where the pilot keeps its pairing token and the port it last bound, so a phone that paired once reconnects
 * without scanning again after the core restarts.
 */
public interface PairingStore {

    /** The kept token, or {@code null} when none was minted yet. */
    String token();

    void token(String token);

    /** The port last bound, or {@code 0} when none was. */
    int port();

    void port(int port);

    /** A store that forgets with the process. */
    static PairingStore inMemory() {
        return new PairingStore() {
            private volatile String token;
            private volatile int port;

            @Override
            public String token() {
                return token;
            }

            @Override
            public void token(String kept) {
                token = kept;
            }

            @Override
            public int port() {
                return port;
            }

            @Override
            public void port(int bound) {
                port = bound;
            }
        };
    }
}
