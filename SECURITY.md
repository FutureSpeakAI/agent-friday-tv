# Security

## Reporting a vulnerability

Please report it privately through GitHub: **Security > Report a vulnerability** on this repository. Don't open a public issue. Include the steps to reproduce, the TV model and Android version, and the app versions (Settings > About & keys on the TV). We aim to acknowledge reports within 7 days.

## What's in scope

- The TV apps: Agent Friday (`ai.futurespeak.friday.tv`), Ad Break Guard (`org.tvprivacy.adbreak`), and the add-on contract and SDK.
- The setup wizard and the Friday desktop extension.

Especially interesting: any way an app or web page on the TV, or a device on the network, could read the keys, make Friday act without the person's say-so, get around the grown-up PIN or kids mode, or call the add-on contract without being enabled.

## How keys are handled (for reviewers)

- Keys are typed on the PC at hidden prompts and streamed over adb's stdin into a content provider that only the adb shell user may call. The TV app seals them at once with the Android Keystore (`SecureStore`).
- They never appear on a command line, in a log, or in a file in the clear. The Friday desktop extension opens a separate window for them, so they never pass through Friday's chat or model.
- Add-ons never receive keys. Friday's voice is shared with add-ons as audio.
- The add-on contract answers only add-ons the owner enabled, and it checks the signing certificate that was pinned at enable time on every call.

## Out of scope

- Third-party AI providers' own services, third-party apps we link to, and the TV maker's firmware.
- Anything that needs someone already able to run adb on your TV. With debugging on, a paired computer controls the TV; switch Wireless debugging off when you're done (the wizard reminds you).
