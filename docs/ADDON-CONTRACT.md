# The Friday TV add-on contract, version 1

An **add-on** is its own Android app that appears on Friday's rail on the TV and can use what Friday shares: her look, her voice, her approval card, her receipts, and the household's kids mode. Friday News is the reference add-on ([friday-tv-news](https://github.com/FutureSpeakAI/friday-tv-news)).

Version 1 is stable. Later versions only add; an add-on that declares `contract="1"` keeps working. All names below are constants in [`Contract.java`](../android/addon-sdk/src/main/java/ai/futurespeak/friday/tv/addon/Contract.java).

## The rules in one paragraph

An add-on **never holds an API key** and never reads Friday's storage. It **does nothing in Friday until the owner enables it**, from the PC setup or from Friday's Settings behind the grown-up PIN. Enabling pins the add-on's signing certificate, so a different app under the same name is not trusted. In **kids mode**, Friday shows only add-ons that are built for kids *and* that a grown-up has marked OK. Anything that spends money or changes the TV goes through **Friday's approval card**, and every call is **receipted**.

## 1. Declare yourself

In your app's manifest, give one activity this action and meta-data. Don't give it a launcher entry; add-ons open from Friday.

```xml
<activity android:name=".MainActivity" android:exported="true">
    <intent-filter>
        <action android:name="ai.futurespeak.friday.tv.addon.OPEN" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="ai.futurespeak.friday.tv.addon" android:resource="@xml/friday_addon" />
</activity>
```

`res/xml/friday_addon.xml`:

```xml
<friday-addon contract="1" id="news" title="News" icon="📰" kids="true"
              triggers="news headlines briefing" />
```

| Attribute | Rule |
|---|---|
| `contract` | `1`. A number Friday doesn't know yet means the add-on is not shown. |
| `id` | `[a-z][a-z0-9-]{0,31}`, unique on the TV. |
| `title` | At most 24 characters; shown on the rail. |
| `icon` | One emoji (at most 4 code points). |
| `kids` | `true` only if the add-on is built for children. It's still hidden in kids mode until a grown-up marks it OK. |
| `triggers` | Up to 8 lower-case words. A spoken request that contains one is offered to you (see 3). |

An invalid declaration is ignored, not repaired. The parser is [`AddonSpec`](../android/addon-sdk/src/main/java/ai/futurespeak/friday/tv/addon/AddonSpec.java).

## 2. Being opened

Friday starts your activity with `ACTION_OPEN`, from its rail, its home screen, the kids home, or a spoken request. Extras:

- `EXTRA_UTTERANCE`: the words, when a spoken request opened you.
- `EXTRA_MATCH`: the Bundle your `match` returned (see 3).

## 3. Spoken requests (optional)

When a request contains one of your trigger words, Friday asks your provider whether the request is yours, and waits at most 1.2 seconds. Extend `FridayAddonProvider`, which answers only the core app, and export it with authority `<applicationId>.friday`:

```java
public final class MyProvider extends FridayAddonProvider {
    @Override protected Bundle match(String utterance) {
        return looksLikeMine(utterance) ? new Bundle() : null;   // quick; no network
    }
}
```

## 4. What Friday shares

Use `FridayTv` from the SDK. Every call returns the quiet answer (null, false, nothing) when Friday is missing, too old, or hasn't enabled you, so your app also works on its own. Call these off the main thread, except `install`.

| Call | What you get |
|---|---|
| `FridayTv.install(app)` | In `Application.onCreate`: the shared Look follows Friday's reduce-motion setting. |
| `coreVersion(c)` | Friday's contract version; 0 when Friday isn't installed. |
| `state(c)` | `kids`, `grownUp`, `motion` (`auto`/`on`/`off`), and the place for the weather (`placeName`, `lat`, `lon`, `fahrenheit`; unset unless the owner chose one). Null when you aren't enabled. **If Friday is installed but you get null, behave as in kids mode.** |
| `capabilities(c)` | What each job uses on this TV: `text`, `voice`, `sound`, `music`, `images` and `video`, each set to a provider name or its fallback (`tv` voice, `stills` pictures, `none`). Without Friday, or before you're enabled, everything is the fallback. **Plan your fallbacks from this**; see [PROVIDERS.md](PROVIDERS.md). |
| `voice(c, text)` | Friday's own voice for up to 1,500 characters, as raw 22.05 kHz 16-bit mono PCM to play. Null means use the TV's text-to-speech. Friday holds the key; you get audio. |
| `ask(c, question, label)` | Opens Friday with the question asked. `label` is the short line the screen shows. |
| `studio(c, title, source, link, text, relatedJson)` | Hands a source to Friday Studio. Studio's own card asks the person before anything is made or spent. |
| `receipt(c, what)` | Adds a plain-words line to Friday's receipts. Never put secrets or personal data in it. |
| `requestApproval(activity, code, question, detail)` | Friday's approval card. The result is `RESULT_OK` only for an explicit yes. "Not now" has the focus, and in kids mode without a grown-up unlock the answer is always no. |

## 5. The Look and the remote

The SDK carries Friday's look (`Brand`, `Look`, `Motion`, `LineIcon`, the `FridayTheme` style, and the Orbitron, Inter and JetBrains Mono fonts under the OFL). It also carries the one input layer for TV remotes (`InputLayer`, `InputGate`), which turns real remote key events into clean steps, and `SafeFetch` with `NetPolicy` for fetching only from hosts you list. Use them, and your add-on will feel like part of Friday.

## 6. Your own duties

- No analytics, no ads, no third-party SDKs that phone home.
- Fetch only from hosts you list. No local-network addresses unless that's your purpose and the person agreed.
- Respect kids mode: filter what you show, and spend nothing.
- Keep working without Friday: every `FridayTv` call can return its quiet answer.

## Versioning

`Contract.VERSION` is 1. A future version adds methods and keys; it never changes or removes these. Friday ignores declarations with a higher `contract` than it knows, so ship `contract="1"` until you need something newer.
