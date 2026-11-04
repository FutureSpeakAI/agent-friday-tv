# Agent Friday™ desktop extension: Friday TV setup

Lets Friday desktop set up Agent Friday on your Android TV: she finds the TV, reads you the remote presses, pairs, installs, and checks it, with an approval card before anything changes on the TV. It is the same wizard as `python -m fridaytv_setup`, offered as tools.

## Add it to Friday desktop (5 minutes)

You need Friday desktop ([Agent-Friday](https://github.com/FutureSpeakAI/Agent-Friday)), Python 3.9 or later, and this repository on your PC.

1. **The tools.** In Friday: **Settings > Connections > Add any MCP server**, choose *by command*, and paste:
   ```
   python -m fridaytv_setup.mcp
   ```
   Set the working folder to this repository's `wizard` folder. Name it `friday-tv`.
   Or add the entry from [`mcp_servers.example.json`](mcp_servers.example.json) to `%USERPROFILE%\.friday\mcp_servers.json` (fix the `cwd` path).
2. **Isolation.** Friday runs new connectors in Windows' isolated mode. This one can't work isolated: it has to reach your TV on your network and run Google's adb. Set its isolation to **off** (the example file already does, with the reason).
3. **The guided flow.** **Settings > Skills > Import** and choose the [`friday-tv-setup`](friday-tv-setup) folder.
4. Say: *"Friday, set up Friday on my TV."*

## What the tools can and can't do

- Reads (`tv_discover`, `tv_setup_guide`, `tv_hardening_checklist`, `tv_verify`) just read.
- Everything that changes the TV is an action, so Friday shows its approval card first.
- **Your API keys never pass through Friday's chat or model.** `tv_provision_keys` opens a separate window on your PC with hidden prompts; the keys go from there to your TV only.
- Hardening refuses anything on the [never-break list](../../docs/NEVER-BREAK.md), whatever is asked.
