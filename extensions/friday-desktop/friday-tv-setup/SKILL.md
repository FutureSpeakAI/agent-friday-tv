---
name: friday-tv-setup
description: Set up Agent Friday on the person's Android TV or Google TV from this PC. Find the TV, walk them through the remote presses, pair, install, add their keys, optional privacy hardening, verify, and undo.
version: 0.1.0
triggers:
  - set up friday on my tv
  - install friday on the tv
  - put agent friday on my tv
  - find my tv
  - friday tv
tool_chain:
  - mcp_friday-tv_tv_discover
  - mcp_friday-tv_tv_setup_guide
  - mcp_friday-tv_tv_pair
  - mcp_friday-tv_tv_connect
  - mcp_friday-tv_tv_install
  - mcp_friday-tv_tv_provision_keys
  - mcp_friday-tv_tv_hardening_checklist
  - mcp_friday-tv_tv_harden
  - mcp_friday-tv_tv_verify
  - mcp_friday-tv_tv_undo_hardening
  - mcp_friday-tv_tv_uninstall_everything
success_criteria:
  - tv_verify shows Agent Friday installed and an Anthropic key present
  - Cast is still discoverable after any hardening
license: MIT
source: https://github.com/FutureSpeakAI/agent-friday-tv
---

# Set up Agent Friday on the TV

Speak plainly; the person may never have used developer settings. One step at a time; wait for them.

1. **Find the TV.** Call `tv_discover`. If there is more than one, ask which (by name). If none: the TV must be on and on the same network; ask for its IP address (TV: Settings, Network & Internet, the network).
2. **Developer options.** Call `tv_setup_guide` with `developer-options` and read the presses to them. Wait until the TV says "You are now a developer".
3. **Wireless debugging and pairing.** Call `tv_setup_guide` with `wireless-debugging`. Ask them to read you the six-digit code and the port after the colon on the pairing screen. Call `tv_pair` with the IP, port and code. The code is single-use and changes each time; if pairing fails, ask for the current one. Older TVs (Android 10 or earlier) have no wireless debugging; use `tv_connect` with port 5555 after they turn on network debugging.
4. **Install.** Explain the helper first: Ad Break Guard reads on-screen ad labels to mute ad breaks, keeps the grown-up PIN and bedtime, keeps Cast awake, and has no internet access. Ask whether to switch it on, and whether they want Friday News. Call `tv_install` with their answers. If no APKs are available, tell them to run the build once in a terminal (the result says how).
5. **Keys.** Never ask for API keys in chat, and never accept one if they paste it: tell them to revoke a pasted key and use the window. Call `tv_provision_keys`; a window opens on the PC where they type their keys hidden. Claude (Anthropic) is required; ElevenLabs, OpenRouter, Gemini, Firecrawl and kie.ai are optional. Explain the fallbacks if they skip one: no ElevenLabs key means the TV's own voice and no sounds or music; no kie.ai key means still pictures. The window also lets them choose models and Friday's voice, and offers an optional town for the weather.
6. **Hardening (optional, off by default).** Only if they want it: call `tv_hardening_checklist`, read each item with what they lose, and apply only the ids they say yes to with `tv_harden`. Never suggest disabling Google's launcher, the Cast receiver, Google Play services, updates, HDMI or the remote; the tools refuse those anyway.
7. **Verify.** Call `tv_verify` and tell them what's there. Then: on the TV, Apps, Agent Friday; hold OK on Talk and ask something.
8. **Undo.** `tv_undo_hardening` reverses hardening. `tv_uninstall_everything` (confirm REMOVE, only after they clearly say so) removes everything; then guide them to switch Wireless debugging off.

Third-party apps such as SmartTube or Projectivy Launcher are not part of this; if asked, give their official pages and let the person install them.
