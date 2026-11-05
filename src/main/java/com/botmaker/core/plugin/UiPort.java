package com.botmaker.core.plugin;

import com.botmaker.plugin.api.Dialogs;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.Theme;

/**
 * The user interface a plugin's dialogs and windows reach through {@link StudioServices}: the look and the choosers.
 * The core has no screen of its own, so it declares this and a client with one, Studio, fills it while attached; with
 * none attached, {@link #NONE} answers, and {@link StudioServices#hasUi()} says false.
 */
public interface UiPort {

    /** No user interface: {@link Theme#PLAIN}, {@link Dialogs#NONE}. */
    UiPort NONE = new UiPort() {
        @Override
        public boolean present() {
            return false;
        }

        @Override
        public Theme theme() {
            return Theme.PLAIN;
        }

        @Override
        public Dialogs dialogs() {
            return Dialogs.NONE;
        }
    };

    /** Whether someone sees what this port shows; only {@link #NONE} says no. */
    default boolean present() {
        return true;
    }

    /** The host's current look, applied to a plugin's own window or dialog. */
    Theme theme();

    /** The choosers, and the window a plugin's dialog is owned by. */
    Dialogs dialogs();
}
