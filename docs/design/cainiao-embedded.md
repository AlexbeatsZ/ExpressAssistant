# 菜鸟单包运行环境

## 用户要求与交付边界

2026-11-16 用户要求全部完成并部署手机：不再单独下载或依赖官方菜鸟；优先在快递内完成登录，停止以抓取日志作为默认认证方式；如保留旧获取方式，只能作为设置「账号与同步」中的选项。身份码页只显示条码、倒计时、必要错误与刷新，刷新图标和文字一起居中。

交付 APK 必须来自 `tools/cainiao-host/build.py`，不是 Gradle 直接生成的界面模块 APK。包名继续为 `com.halo.expressassistant`，签名继续为已有个人签名，因此覆盖升级保留原 UID、AndroidKeyStore 和数据。

## 原始运行环境与自己的界面

- 使用项目内已保留的 8.11.1023 arm64 APK，不下载新菜鸟，不安装第二个应用，不需要 LSPatch Manager 或系统 LSPosed。
- 采用 JingMatrix/LSPatch v1.2/487 的内置模块和 level 2 签名兼容机制，保留原始 APK 的代码、资源和原生安全环境；外围安装包仍由个人签名签署。
- 外层清单合并现有快递组件，唯一 Launcher 是自己的 MainActivity；外层应用名称/图标采用快递。原始应用只承担内部环境，自己的组件通过独立模块类加载器、Context 和 Application 运行。
- Android 资源编号分开：原始运行时 `0x7f`，快递界面 `0x7e`。ARSCLib 合并两份表；外层快递资源文件改为 `res/express/`，模块内仍保持原路径。不能覆盖原表或复用同一资源编号，否则启动时 networkSecurityConfig、主题或界面引用会失配。
- 原包身份对应的自定义权限、provider authority、进程及 taskAffinity 在外层清单映射到当前包，避免与手机已有菜鸟冲突；组件 Java 类名保持原值。
- Android11+ 使用系统 ResourcesLoader 将界面模块的0x7e资源加入框架管理的Resources，保留原始0x7f资源，交给系统处理配置变化。不要在Android16上用孤立Resources实例承载整套界面，否则明暗色切换后Drawable/ColorStateList会保留旧色板。API28/29保留独立Resources与有界重建兼容路径，尚未在旧系统验收。
- 原版 Welcome/HomePage/广告 Activity 在本进程中转到自己的 MainActivity，避免内部跳转露出原版首页；协议和验证页仍按正常登录流程显示。
- 原始资源和签名兼容机制只在本应用进程内生效，不改变 Android 系统的安装签名规则，也不等于伪造系统证书。
- 当前整合宿主的实际最低 API 为 28、target API 为 30，界面模块仍以 API 36 编译。改变宿主 targetSdk 之前必须重新验证旧 SDK 的 receiver、服务、WebView 与验证交互，不能直接沿用界面模块的 targetSdk。

## 原生签名与续登

`CainiaoNativeRuntime` 在集成模式直接使用 HostedCainiao 保存的原始 Application 与 ClassLoader，不调用 `createPackageContext(com.cainiao.wireless)`。旧安装加载器仅作为普通开发构建的兼容路径；正式交付必须检测到集成宿主。

仍沿用已有原生请求实现和会员对象：`autologin`、包裹详情、取码授权和取码。H5 身份码/材料接口继续优先使用有效 Cookie，过期后由内置原生运行时续登；新签名每次生成，不复用捕获的签名。

原生安全组件插件、资源及 JNI 动态依赖保持完整。此前最小 190 类裁剪试验的 203 只能说明安全配置读取失败，不能据此断言个人签名不可用；完整原包上下文已验证跨过该错误。

## 在本包内登录

- 默认入口为设置「账号与同步 → 菜鸟登录 → 登录菜鸟」。由用户主动操作内置 SDK 的正常手机号、验证码和验证页面，不读取另一个应用的文件或日志。
- 按原始 LoginInitJob 的消费者配置初始化 CnmcConfig：tenant 78、CustomerAppProvider、实例 `cainiao2c`、ONLINE、原始 TTID 与 productVersion `1.0.0`，再调用 CnmcLogin.login。保留正常协议和验证交互。
- 直接接收 LoginInfoContainer.saveSessionInfo 的认证结果，经原始 JSON 序列化转换为最小会员对象；同时读取同一 SDK 的设备参数。此结果不是 logcat 抓取。
- 只接受本次用户登录所触发的结果；检查消费者域、会员期限、Cookie 与设备参数，再调用既有 H5 在线码和材料接口验证。全部通过才在续登互斥锁内加密保存；失败保留原有有效会话。
- 原始 SDK 的常见会话凭据日志在本进程内抑制。程序不把验证码、Cookie、sessionId、refreshToken 或完整会员结果写入验收日志。
- 过渡入口「从已登录菜鸟获取」仅在对应应用可打开时出现，放在同一设置页面；身份码页没有获取按钮、Root 说明或打开菜鸟按钮。获取通知也回到设置。

## 本轮已经验证

- Android 16 arm64 隔离模拟器：只安装个人签名的整合包、不安装 Manager，保留当前快递包名，读取原生 AppKey 正确，生成五项签名字段。自己的主页、身份码、设置及包内手机号登录页能打开；系统明暗色实时切换、刷新图文居中和透明系统栏通过。
- K70：整合版 0.9.15/code29 签名覆盖安装成功，原 37 件快递与小米/京东/淘宝/拼多多各 1 账号保留。
- 停用原官方菜鸟并冷启动快递后，原生会员续登、H5 在线身份码与材料刷新均返回 200，续登期限向后推进。此验证证明当前续登与身份接口不需要原版运行或加载其安装文件。
- 2026-11-16 用户在手机完成验证后明确回复「验证完成,直接发布即可」。新的包内登录按用户本人验收通过记录，解除 v0.9.15 的发布草稿条件；本次发布沿用手机已安装、用户已验证的 APK，不重新构建或替换附件。

## 验证与恢复

- 构建入口和依赖固定在 `tools/cainiao-host/README.md`；签名与输入 APK 保持忽略。
- 本轮私有手机升级前备份与脱敏结果在 `outputs/.private/cainiao-runtime/single-package-20261116/`，备份包括原配置和加密会话；不要将备份、材料或实际码提交或上传。
- 先验证原生签名/服务器续登，再验证新的手机号登录、包裹/取件信息、身份码、冷启动与后台 Worker。实物驿站核销仍需独立观察，不能由构建或联网成功推定。
