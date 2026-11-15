# Pixel branding and Settings appearance

2026-11-15: user supplied pixel-icons-2026.zip and requested names 手环 / 洗衣机 / 快递, plus Google Settings inspired appearance.

## Reference implementations
- AOSP SettingsLib/Spa: https://github.com/aosp-mirror/platform_frameworks_base/tree/main/packages/SettingsLib/Spa
- Android Now in Android settings: https://github.com/android/nowinandroid/tree/main/feature/settings

Use existing Material/AndroidX components, large page titles, semantic surface colors, 16–24dp spacing and grouped preference rows. Follow system light/dark mode; preserve dynamic colors where already supported. Do not add a web runtime or migrate application architecture for appearance alone.

## Application scope
- App name 快递. Replace all launcher layers and point the manifest to mipmap icons.
- Widgets use the same mipmap launcher icon and app name. Remove superseded launcher vectors after replacing their references.
- Settings sections: 账号与同步, 快递管理, 使用偏好, 关于. Preserve every row and summary binding and interaction.
- Home title 32sp; maintain existing fixed navigation, dynamic color and import logic.
- Keep upstream attribution and license information in the About screen.
