# Integrated Cainiao build

The release is one APK with package `com.halo.expressassistant`, the existing personal signing key, the ExpressAssistant interface, and an embedded original runtime. The build never downloads or installs Cainiao. It requires the existing local runtime input and Java 21 or newer, the Android SDK, and the project's signing configuration.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 tools/cainiao-host/build.py \
  --java-home /path/to/existing/java-21 \
  --android-sdk /path/to/existing/android-sdk \
  --runtime outputs/cainiao-8.11.1023.apk
```

Deliver `outputs/ExpressAssistant-v<version>.apk`. The APK from the Gradle `app/build/outputs` directory is the interface module used during composition. It is not the integrated deliverable.

The composition uses JingMatrix/LSPatch v1.2 build 487 in embedded-module mode, without Manager, with signature compatibility level 2. ARSCLib V1.4.0 merges the original resource package `0x7f` and ExpressAssistant package `0x7e`. The original runtime APK remains unchanged inside the final APK. Interface resource files use `res/express/` in the outer APK to avoid pathname collisions. The builder reuses existing personal signing credentials without placing passwords on the command line.

Only the open source build helpers are downloaded into the project's ignored tool cache. Original APKs, signing files, account material, tool binaries, intermediate APKs, and private verification output stay outside Git. Temporary composition files are removed after each build.

Upstream build tools:

- JingMatrix/LSPatch: https://github.com/JingMatrix/LSPatch
- REAndroid/ARSCLib: https://github.com/REAndroid/ARSCLib
- Xposed compile-only API: https://api.xposed.info/

See `docs/design/cainiao-embedded.md` for runtime and authentication boundaries.
