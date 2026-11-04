# Never break

These rules come from real incidents while hardening Google TV sets. The wizard enforces them in code ([`harden.py`](../wizard/fridaytv_setup/harden.py)), and tests check them. A hardening profile cannot override them.

1. **Wi-Fi and the home screen.** Never disable `com.google.android.apps.tv.launcherx`, Google's TV launcher. On at least one Google TV, disabling it erased the saved Wi-Fi network and broke Cast. If you want a different home screen, install one (for example Projectivy Launcher) and choose it; don't disable Google's.
2. **Google Cast stays discoverable.** Never disable or force-stop:
   - `com.google.android.apps.mediashell` (the Cast receiver)
   - `com.google.android.gms` and `com.google.android.gsf` (Google Play services)
   - `com.android.tv.mdnsoffloadmanager` (answers Cast discovery while the TV sleeps)
   - anything with `googlecast` in its name (vendor Cast components)

   After every hardening batch, the wizard checks that the TV still answers Cast discovery, and puts the batch back if it doesn't.
3. **DNS filters must not block Cast and setup.** If you add a DNS filter yourself (NextDNS, Pi-hole and the like), don't block Google's Cast and setup domains: `*.gvt1.com`, `*.googleapis.com`, `clients*.google.com`, `connectivitycheck.gstatic.com`, and Google's Cast and setup hosts. The wizard doesn't change DNS.
4. **The remote, HDMI-CEC, inputs, updates and the Play Store keep working.** Anything named for OTA, updates, CEC, HDMI, TV inputs, the remote, Bluetooth, the keyboard, settings, the package installer or WebView is refused.
5. **Ad Break Guard stays an accessibility service while it's installed.** It keeps the Cast receiver out of Android's app freezer on some TVs. Without it, those TVs vanish from Cast after a while. Don't force-stop it, since that unbinds the service. Uninstalling it through the wizard switches it off cleanly first.
6. **Kids mode can always be left, and the PIN can always be reset.** Uninstalling Ad Break Guard, or `setup.ps1 uninstall`, always gets a grown-up out.

**If Cast disappears anyway:** run `setup.ps1 undo --device <ip:port>`, then restart the TV (unplug it for 30 seconds). If it's still missing, re-enable anything you disabled by hand with `adb shell pm enable --user 0 <package>`.
