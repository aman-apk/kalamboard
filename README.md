# KalamBoard — كلام بورد

Arabic/English keyboard for Android with on-device word prediction that learns from you, dialect dictionaries (Levantine, Egyptian, Gulf), typo-tolerant correction, and themes. The package has no `INTERNET` permission; a build guard fails the build if a networking library or the permission is ever added. Check it yourself: Settings → Apps → KalamBoard → Permissions.

KalamBoard is a modified version of [FlorisBoard](https://github.com/florisboard/florisboard) (Apache-2.0). `NOTICE` lists what we changed; `README.florisboard.md` is the upstream README. Upstream remains the place for FlorisBoard itself; issues about KalamBoard belong here.

Part of the [Aman Labs](https://amanlabs.app/en/) family of Arabic-first privacy apps. Signed builds and verification fingerprints: [aman-releases](https://github.com/aman-apk/aman-releases).

## Build

Standard Android Gradle project (`./gradlew :app:assembleRelease`). Release signing reads `keystore.properties` from a `keystore/` folder outside the repository; without it the build produces an unsigned APK.

## License

Apache License 2.0, same as FlorisBoard. See `LICENSE` and `NOTICE`.
