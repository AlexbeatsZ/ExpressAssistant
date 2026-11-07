# 主题与外观

## 固定莫奈取色

2026-11-06 起，手机端只保留莫奈取色，设置页不再提供主题入口。

- 所有 Activity 通过 `Themes.apply` 使用 `Theme.ExpressAssistant`。
- 主题继承 `Theme.Material3Expressive.DynamicColors.DayNight.NoActionBar`，配色跟随系统壁纸，昼夜跟随系统；不支持动态取色的设备使用 Material 的回退配色。
- 字体采用系统无衬线，不再探测或覆盖中文衬线字体。
- 页面根背景继续使用 `?attr/surfaceBackground`，不覆盖 Material 卡片的背景与按压反馈。
- 温暖纸感、自定义组合、昼夜独立配置、纸纹强度及对应素材/样式已删除。

## 升级处理

`App.onCreate` 调用 `Store.clearLegacyThemePreferences`，只删除旧主题、配色、字体、纸纹及昼夜独立配置的 11 个偏好键；重复启动不再写入。账号、Cookie、快递记录、身份码材料和其他设置保留。清理旧主题后刷新现有桌面小组件，避免继续显示暖色衬线布局。

主题是固定的，主页返回时不再为了主题变化调用 `recreate()`。系统昼夜变化仍使用 Android 默认的配置重建。

## 桌面小组件

只保留默认无衬线布局：`widget_express_list`、`widget_express_list_compact`、`widget_express_small`；按现有尺寸规则选择。旧暖纸衬线布局已移除。小组件仍使用现有 `widget_*` 色板，并通过 `values-night` 跟随桌面深色模式，不提供独立主题设置。
