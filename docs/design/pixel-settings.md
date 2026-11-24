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

2026-11-16: 菜鸟登录加入账号与同步。默认包内手机号登录，旧 Root 获取是该设置下的可选连接方式；身份码页仅保留出码和刷新。

## 2026-11-22 登录失效提醒

用户已确认 Pixel 风格预览并授权部署。主页在搜索下方显示24dp圆角、零阴影的 Material errorContainer 提醒卡，标题“登录已失效”，列出受影响平台、保留旧快递记录说明与“去处理”。一个失效平台直达其账号面板，多个失效平台先选择平台。设置“账号与同步”入口显示失效数量、语义错误色摘要与提示图标；账号卡显示实际登录状态，失效时给出“重新登录”主按钮，保留启用开关及管理动作。菜鸟仍使用包内SDK登录，拼多多继续保留网页登录影响官方App的既有提示。所有配色使用原生主题属性，明暗色跟随系统。

登录状态独立于“启用同步”。仅明确认证失败标记EXPIRED，网络、超时、网关拒绝和部分响应不证明登录已失效或已恢复。已禁用账号不触发主页提醒，但账号面板仍展示其失效状态。购物平台状态按channel+账号ID保存，重新登录按稳定完整平台身份匹配原账号（忽略自定义标签），保留账号ID、标签及启用状态。登录代次递增，凭据轮换保留代次；请求完成时校验账号仍启用、payload及代次匹配，旧请求不能覆盖新登录。仅有效订单响应、有效身份码响应或用户完成登录可清除提醒，Cookie存在或轮换本身不作为认证成功。

菜鸟状态来自加密会话的明确认证错误和会员续登错误，错误元数据只镜像为不含凭据的UI状态；普通网络错误不会清除已确认的失效。主页从冷启动读取旧会话错误，前台订阅状态变化；设置摘要及已打开的购物账号面板同步更新。既有离线身份材料和快递缓存保留。
