package com.botmaker.core.run.capture;

/**
 * The single source of truth for the graphics session BotMaker is running under: whether it is Wayland, whether
 * this is Linux, and which desktop. Until 2026-11-09 it also built a command installing the distro's X11 session
 * packages; neither Studio nor a bot needs an X11 session now (Studio's {@code WaylandNotice}), and Plasma 6.8 has
 * none to install.
 */
public final class SessionEnvironment {

    private SessionEnvironment() {}

    /** True if we appear to be running under a Wayland session (the strongest of the env signals). */
    public static boolean isWayland() {
        return "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))
                || System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** True on Linux (where the X11-vs-Wayland distinction applies). */
    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** The current desktop environment string ({@code XDG_CURRENT_DESKTOP}), lower-cased; never null. */
    public static String currentDesktop() {
        String d = System.getenv("XDG_CURRENT_DESKTOP");
        return d == null ? "" : d.toLowerCase();
    }
}
