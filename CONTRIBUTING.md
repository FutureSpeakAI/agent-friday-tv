# Contributing

Thanks for helping. Bug reports, TV compatibility reports ("works on my Hisense U8"), fixes and add-ons are all welcome.

## Before you open a pull request

- **Run the tests.** `cd android && ./gradlew testReleaseUnitTest` and `python -m unittest discover -s wizard/tests`. CI runs the same.
- **Keep it private by design.** No analytics, ads, crash reporters or third-party SDKs in the apps. Tests fail if a runtime dependency appears. Network hosts are listed explicitly; nothing reaches the local network unless that's the point and the person agreed.
- **New pictures, sounds, fonts or other binaries** go in `ASSET-SOURCES.json` with their SHA-256, origin and license. `python scripts/publish_guard.py` (also run by CI) fails on anything unlisted, on anything generated for a household (stories, designed voices, reports, recordings, a child's answers), and on voice ids that aren't ElevenLabs' public premade voices.
- **Never commit secrets or personal data.** No API keys, keystores (`*.jks`, `*.keystore`, `keystore.properties`), IP or MAC addresses of real devices, serial numbers, names, screenshots of a real household, or logs. `.gitignore` covers the usual files; CI runs gitleaks on every push.
- **Respect the never-break rules** in [docs/NEVER-BREAK.md](docs/NEVER-BREAK.md). Hardening changes go in `wizard/fridaytv_setup/profiles/*.json`, and each item needs a plain "what it is", "what you lose" and an undo. The wizard's tests check every item against the rules.
- **Write for newcomers.** On-screen and wizard text is plain language, says what will happen before it happens, and always says how to undo it.
- **Don't add third-party APKs.** Link to their official sources instead.

## Vendor hardening profiles

To add a profile for your TV brand, copy `profiles/generic.json`, set `manufacturers` to the lower-case maker names the TV reports (`adb shell getprop ro.product.manufacturer`), and list only packages you've verified on that brand, with a source for each claim. Test it on your own TV with `setup.ps1 harden --dry-run --device …` first.

## Add-ons

Add-ons live in their own repositories and use the [add-on contract](docs/ADDON-CONTRACT.md). Contract changes are additive only. Propose one in an issue first.

## Code style

Match the code around you: small classes, comments that say why, tests that can fail. Java 17, no Kotlin, no AndroidX (the apps stay dependency-free). Python is standard library only.

By contributing, you agree that your contribution is licensed under the MIT License of this repository.
