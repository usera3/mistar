# Third-party notices

The root MIT license covers Mistar's original application code and documentation. Third-party code retains its own license and copyright notices.

## Redistributed source and tooling

- **Concentus / Opus Java port** — `android/app/src/main/java/org/concentus/`; BSD-style terms retained in each source file and in [CONCENTUS-LICENSE.txt](android/app/src/main/assets/CONCENTUS-LICENSE.txt), also copied to `LICENSES/Concentus.txt`. Upstream: <https://github.com/lostromb/concentus>.
- **Gradle Wrapper 8.13** — `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.jar`; Apache License 2.0, included in `LICENSES/Apache-2.0.txt`. Upstream: <https://github.com/gradle/gradle>.
- **FunASR public speech example** — native-test-only `dictation/funasr_zh.wav` is the 16 kHz mono PCM from [runtime/funasr_api/asr_example.wav](https://github.com/modelscope/FunASR/blob/main/runtime/funasr_api/asr_example.wav), with a normalized WAV header; `voice-text/public-speech.m4a` is an AAC test rendition. Upstream's MIT notice is retained in `LICENSES/FunASR-MIT.txt`. These are public samples, not user recordings, and are not bundled into the main application APK.

## Dependencies resolved at build or install time

| Dependency | Upstream | License |
| --- | --- | --- |
| AndroidX Core | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| Google Tink (Android and Python) | https://github.com/tink-crypto | Apache-2.0 |
| OkHttp | https://github.com/square/okhttp | Apache-2.0 |
| WebRTC Android SDK | https://github.com/webrtc-sdk/android | BSD-style; see upstream notices |
| Kotlin | https://github.com/JetBrains/kotlin | Apache-2.0 |
| aiohttp | https://github.com/aio-libs/aiohttp | Apache-2.0 |
| Pillow | https://github.com/python-pillow/Pillow | HPND; see upstream LICENSE |
| aiortc (optional bot call example) | https://github.com/aiortc/aiortc | BSD-3-Clause |

Transitive dependencies may have additional notices; retain the notices distributed with your actual build. No AI model weights, cloned voice assets, personal knowledge graph or image gallery are included in this repository.

The optional WeType adapter interoperates with a third-party online speech service. That service and its availability are not provided or licensed by this repository. No original browser-extension bundle, logged-in input-method state, API account credential or vendor service entitlement is redistributed here. QQ is referenced only as an interaction reference; this project does not include Tencent QQ code or assets or connect to QQ accounts.
