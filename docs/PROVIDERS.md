# Bring your own keys and models

Agent Friday for TV uses AI services that **you** connect with **your own** keys. You choose which service does each job and with which model. A job without a key falls back to something that still works on the TV, so you can start with just a Claude key and add the rest later.

## The jobs, the services, and the fallbacks

| Job | Service (default model) | Key needed | Without the key |
|---|---|---|---|
| **Answers and stories** (text) | Anthropic Claude (`claude-opus-5-5`; quick model `claude-sonnet-5-5`), or OpenRouter | Anthropic, or OpenRouter | Friday can't answer yet. The TV says a grown-up needs to add a key. |
| **Friday's voice** | ElevenLabs (`eleven_flash_v2_5`, a premade voice) | ElevenLabs | **The TV's own voice** (Android text-to-speech), offline. |
| **Story voices** (designed per character) | ElevenLabs Voice Design (`eleven_multilingual_ttv_v2`) | ElevenLabs | Everyone speaks in the TV's voice. |
| **Sound effects** | ElevenLabs sound generation | ElevenLabs | No sound effects. |
| **Music** | ElevenLabs music | ElevenLabs | No generated music. |
| **Pictures** | kie.ai (`nano-banana-2`) | kie.ai | **Still pictures**: add-ons show their own illustrations or public photos, or none. |
| **Short video clips** | kie.ai (a video model you choose; off by default) | kie.ai | **Stills** with a slow drift. |
| Live voice conversation (optional) | Google Gemini Live | Gemini | Not offered. |
| Reading web pages (optional) | Firecrawl | Firecrawl | Friday answers from what she knows. |

Your choice beats a key: if you pick the TV's voice, Friday uses it even when an ElevenLabs key is present.

## Where the keys go

- You type each key at a hidden prompt in the setup wizard (`setup.ps1` > Keys).
- The keys travel over your own network to your TV, where the Android Keystore seals them. They go nowhere else except the provider that issued them.
- They never appear on screen, in a log, in a file, or on a command line. Add-ons never receive them.
- To remove the keys: *Settings > About & keys > Remove* on the TV, or `setup.ps1 uninstall`.

## Choosing models

**Interactively:** in the Keys step, answer *yes* to "Choose models and Friday's voice yourself?". The wizard lists the Claude models and ElevenLabs voices on *your* accounts.

**With a file:** copy [`providers.example.json`](../providers.example.json), edit it, and run:

```bash
python -m fridaytv_setup keys --device <ip:port> --providers my-providers.json
```

```json
{
  "text":   { "provider": "anthropic", "model": "claude-opus-5-5", "fast_model": "claude-sonnet-5-5" },
  "voice":  { "provider": "elevenlabs", "voice_id": "21m00Tcm4TlvDq8ikWAM", "model": "eleven_flash_v2_5" },
  "sound":  { "provider": "elevenlabs" },
  "music":  { "provider": "elevenlabs" },
  "images": { "provider": "kie", "model": "nano-banana-2" },
  "video":  { "provider": "none" }
}
```

- Leave anything out to keep its default. A value the TV doesn't recognise also takes the default.
- `provider` can be `"tv"` (voice) or `"none"` (sound, music, pictures, video) to switch a paid service off even when its key is present.
- **The file never holds a key.** The wizard refuses a file with anything shaped like a key in it.

## Checking what's in use

`setup.ps1 verify --device <ip:port>` prints a *Services in use* line, for example `text=anthropic voice=tv images=stills`: Claude for answers, the TV's voice, and still pictures.

Add-ons ask Friday the same question through the [add-on contract](ADDON-CONTRACT.md) (`capabilities`), and plan their fallbacks from the answer. They never see a key.

## Costs

Every paid call is billed to you by the provider whose key it used. Paid calls show up on Friday's receipts (Settings) with the model that made them. In kids mode, paid parts stay off unless a grown-up unlocks them with the PIN.
