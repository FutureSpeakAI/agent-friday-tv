package ai.futurespeak.friday.tv.web;

/** Test access to SandboxView's pure parts (same package). */
public final class SandboxViewTestAccess {
    private SandboxViewTestAccess() {}
    public static String wrap(String html) { return SandboxView.wrap(html, "default-src 'none'"); }
    public static String backJs() { return SandboxView.BACK_JS; }
}
