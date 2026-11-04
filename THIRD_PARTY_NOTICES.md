# Third-party notices

This repository's own code is MIT-licensed (see [LICENSE](LICENSE)). It also includes these third-party parts, under their own licenses:

| Component | Where | License |
|---|---|---|
| sherpa-onnx Java API and JNI libraries (v2.0.1), by the k2-fsa project | `android/tv/src/main/java/com/k2fsa/sherpa/onnx/`, `android/tv/src/main/jniLibs/` | Apache License 2.0, https://github.com/k2-fsa/sherpa-onnx |
| three.js r128 and its post-processing examples | `android/tv/src/main/assets/avatar/desktop/three-r128*.js` | MIT, Copyright 2010-2021 Three.js Authors |
| Orbitron font | `android/addon-sdk/src/main/res/font/orbitron.ttf`, avatar assets | SIL Open Font License 1.1 (`OFL-Orbitron.txt`) |
| Inter font | `android/addon-sdk/src/main/res/font/inter.ttf`, avatar assets | SIL Open Font License 1.1 (`OFL-Inter.txt`) |
| JetBrains Mono font | `android/addon-sdk/src/main/res/font/jetbrains_mono.ttf` | SIL Open Font License 1.1 (`android/addon-sdk/OFL-JetBrainsMono.txt`) |

The optional on-TV speech model (sherpa-onnx streaming Zipformer English 20M) is not in this repository. If you choose it, the wizard downloads it from the k2-fsa project's release page; it is Apache-2.0.

Friday's avatar scene (`assets/avatar/desktop/scene.js`, `vibe_api.js`) is copied from [Agent Friday desktop](https://github.com/FutureSpeakAI/Agent-Friday) (MIT, FutureSpeak.AI).

Apps the wizard links to, such as SmartTube and Projectivy Launcher, are not included or redistributed.
