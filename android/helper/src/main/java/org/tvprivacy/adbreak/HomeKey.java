package org.tvprivacy.adbreak;

/**
 * The remote's Home key (launcher step L2). Ad Break Guard takes Home only when:
 *  - the keymap has an explicit Home entry, and
 *  - Projectivy's accessibility service is NOT enabled (so the two never fight over the key).
 * Plain Java so it can be unit-tested.
 */
final class HomeKey {
    private HomeKey() {}

    static final int KEYCODE_HOME = 3;   // android.view.KeyEvent.KEYCODE_HOME
    static final String PROJECTIVY_PKG = "com.spocky.projengmenu";

    /** enabled_accessibility_services is "pkg/cls:pkg/cls..."; true if any Projectivy service is in it. */
    static boolean projectivyOn(String enabledServices) {
        if (enabledServices == null || enabledServices.isEmpty()) return false;
        for (String s : enabledServices.split(":")) {
            if (s.trim().startsWith(PROJECTIVY_PKG + "/")) return true;
        }
        return false;
    }

    /** Should Ad Break Guard consume this key as Home? */
    static boolean take(int keyCode, boolean mapped, String enabledServices) {
        return keyCode == KEYCODE_HOME && mapped && !projectivyOn(enabledServices);
    }
}
