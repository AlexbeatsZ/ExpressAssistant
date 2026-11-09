# 菜鸟消费者直接登录研究

## 当前结论（2026-11-07）

独立手机号首次登录尚未实现。会员续登已经用本人真实 refreshToken 跑通：原生网关返回有效 SessionInfo 和 loginCookie，换 Cookie 后 H5 材料刷新成功；v0.9.6 接入加密会员状态、原生签名运行时和自动恢复，手机内独立进程验收见下节。正式版不放置不能完成首次登录的入口。

## 长期续登（v0.9.6）

- 目标为第三方客户端持续登录，不能把材料刷新成功等同会员续登成功，也不能承诺永久免登录。
- 原生 `mtop.cainiao.cnmember.customer.autologin` 的根字段是 `autoLoginRequest`，值为序列化 JSON 字符串，包含 accountId、refreshToken，以及 BaseLoginRequest 的 appKey=23050477、tenantId=78、utdid、sdkVersion（本轮6.0.3.2）。缺 appKey/sdkVersion 会分别返回7000003/7000006，不能误报为 Token 失效。
- 消费者请求必须带 `x-biz-type=cainiao_c_user` 和 `x-biz-info=env=cainiao_c_user`；遗漏时原生入口返回 SERVICE_NOT_EXIST。普通 H5 即使补齐这些字段仍返回 UNAUTHORIZED_ENTRANCE。
- 使用本人手机已安装且证书匹配的官方菜鸟8.11.1023中的 SecurityGuard 接口生成新签名。快递助手加载已安装的官方代码，SDK数据写到自己的存储；不复制/发布官方SDK，不启动官方App或借用其进程，不需要ADB常连。官方App作为运行时依赖需保留安装；尚不属于无官方运行时的独立实现。
- member/native（原设备utdid/ttid）与Cookie、材料一起存入原有AndroidKeyStore/noBackup加密会话；初始化维护API可只补充会员与设备上下文，不修改平台账号/快递。会员返回必须同账号、消费者tenant78、有效期限完整；先保存轮换凭证，再合并loginCookie并重试读取。
- 每12小时的现有Worker同时检查会员和材料；会员剩余不足13小时主动续登；在线读取遇SESSION_EXPIRED时在互斥锁内续登并只重试一次。材料仍采用剩余不足一天才补抓的策略，二者独立。
- 临时网络失败保留有效材料与会员对象；明确401/402拒绝才标记需重新认证。初始登录、长期跨天/跨30天运行以及真实驿站扫码验收边界分别记录，不能混为一次成功。
- 2026-11-07 研究验收：旧Cookie真实材料刷新SESSION_EXPIRED；独立临时进程生成新原生签名，真实autologin成功；该次sessionId/refreshToken值未变化、两项期限均滑动向后，loginCookie包含cnMobileSid/FROM_TYPE/cacbi。合并后材料更新SUCCESS、同userIndex、旧密钥保留。此为协议验收，正式APK验收另记。
- K70正式APK验收：0.9.6/20签名覆盖保留203件和JD/TB/PDD各1账号；只导入会员及原设备上下文，保留真实失效旧Cookie。首次online/refresh自动触发原生续登后200/source=online，随后session/refresh与材料refresh均200；needsLogin=false。官方菜鸟进程停止，手机临时签名探针删除后强停/冷启动快递助手，再次原生续登与在线刷新均成功。真实会员期推进至11月8日、续登凭证至12月7日、材料至11月22日；仅代表本次滑动延长，未跨30天观察。实屏条码/倒计时无失效提示。37项单测、lint零错误、release签名构建通过。

此前将 cnlogin.cainiao.com 作为首选的判断需要修正：其公开网页固定使用商家账号域 21/cainiao，消费者 App 使用 78/cainiao2c。存在短信表单不能证明登录后可取得消费者身份码。

早期探测只做公开网页读取、本人保留APK的静态分析和无手机号负面对照。后续用户明确允许临时安装官方菜鸟，并亲自在官方App完成短信登录、打开身份码；程序没有代发短信或接管验证码。2026-11-06仅恢复同一账号的新会话，当时独立首次登录与自动续登均未实现；2026-11-07续登进展见上节。

## 三类不同的有效期

1. 显示的离线动态码按服务器下发的 period 换码，本人材料为 600 秒；同一周期内重复计算得到同一码。
2. 离线材料 expireTime 是缓存的绝对毫秒期限。初始材料期限为2026-11-19；本人本次重登恢复后已推进至2026-11-21，与是否能联网刷新不同。
3. 菜鸟会员 sessionId 有独立期限，并由 refreshToken 续登。只导入 Cookie 和离线材料没有取得完整续登能力。

2026-11-06 原型及 K70 的材料更新均收到 FAIL_SYS_SESSION_EXPIRED。实屏跨过十分钟边界后条形码确实改变，且与独立生成器一致。因此这次故障是联网会话失效，不能写成离线计时器停止。

## 官方网页验证

GET https://cnlogin.cainiao.com/ 返回「菜鸟统一登录」。viewConfig 的短信发送、登录、注册分别为 /sms/send、/sms/login、/sms/reg，配置均为 appName=cainiao、fromSite=21。页面使用 AWSC/Baxia 验证交互。

负面对照结果：

| 候选 | 本轮实际结果 | 可得结论 |
| --- | --- | --- |
| cnlogin 添加 appName=cainiao2c、fromSite=78、cnSite=CAINIAO_C | 仍返回 cainiao/21 | 参数没有把商家页面变成消费者页面 |
| login.m.taobao.com/login.htm 添加消费者参数 | 重定向标准淘宝 H5，表单 appName=taobao | 不能作为消费者会话已取得的证据 |
| havanalogin.taobao.com/mini_login.htm 的消费者 appName 候选 | 非法请求 appNameError | 缺少正确且被网关允许的应用配置 |
| 常见 H5 appKey 12574478 的 customer.autologin 无业务参数预检 | FAIL_SYS_UNAUTHORIZED_ENTRANCE，无新 H5 token | 当前普通 H5 入口不能直接承担已还原的会员续登 |
| 相同 H5 入口的 smsSend 无手机号预检 | RGV587_ERROR，无新 H5 token | 候选入口有验证要求，未发送短信 |

这些失败仅排除了本轮具体入口/参数组合，不能推断所有 H5 登录或全部原生登录均不可能。不得重复请求验证码或绕过验证。若后续官方页面正常要求绑定或协议，必须呈现给用户。

## Android 8.11.1023 的实际链路

CnmTenantEnum 定义：CAINIAO_B(21,cainiao,1)、CAINIAO_C(78,cainiao2c,0)、CAINIAO_C_OVERSEA(84,cainiao2c_oversea,0)。CnmcDefaultAppProvider.getSite() 返回 CAINIAO_C；短信优先，不支持密码登录。

UserLoginServiceImpl 构造 mtop.taobao.mloginService.smsSend / smsLogin 1.0。loginInfo 包含 site=78、appName=应用 appKey（原生请求已观察为 23050477）、deviceId、smsSid、sdkVersion、ttid、utdid、时间；登录还带 smsCode。riskControlInfo 由官方安全组件生成，某些分支另有 deviceTokenSign。静态字段只能说明代码构造，不证明某字段可以省略，也不证明登录接口要求预先有业务会话。

短信验证成功之后不是立即完成菜鸟会员登录：

1. 阿里登录成功 filter 调用 Login.getOneTimeToken()，该值来自登录 SDK 会话，不是现有 Cookie 中可直接找到的字段。
2. LoginRequest.havanaSsoToken 发送到 mtop.cainiao.cnmember.customer.login。
3. 成功响应 SessionInfo 有 sessionId、refreshToken、cnAccountId、tenantId、ext。
4. LoginInfoContainer 将完整 SessionInfo 经安全服务加密后保存到本地 session/session_key；现有项目只导入的 Cookie 不含该对象。
5. 会话检查读取 ext.sessionIdExpired（绝对毫秒）；sessionId 和 cnAccountId 均需存在。缺少期限等分支有兼容降级，不能从此推定永不过期。
6. 后续登录若存在 refreshToken，AutoLoginRequest 发送 refreshToken + accountId 到 mtop.cainiao.cnmember.customer.autologin；成功保存新会话并刷新淘宝会话。

接口声明 NEED_SESSION=false、NEED_ECODE=false 不代表无签名/无环境验证。CustomerAppProvider 的 ONEKEY_LOGIN_KEY 属于运营商一键登录，不应误当成短信登录万能密钥。

## 公开实现复核

2026-11-06 搜索 GitHub 后实际读取 jieyangxchen/parcel-pilot-mcp 的 README 和 src/providers/cainiao-provider.ts（main=b8761d08148f56e462b79a58da4d06acfb94563c）。其 login() 只是访问 www.cainiao.com 并返回 manual_action_required；同步也是官网截图/HTML 选择器框架，README 明确菜鸟真实页面仍需验证。它没有消费者 smsSend/smsLogin、会员 SSO 或 refreshToken 实现，不能用于填补本项目缺口。本轮未找到可直接复用且已验证的消费者登录实现；这不是证明不存在。

## 下一步实现与验收

- 先确认受支持的消费者登录入口，或明确原生 SDK 初始化、应用配置、风控和正常验证交互。不要继续将商家登录页塞进手机。
- 若正常登录获得阿里会话，继续会员 SSO 转换，保存完整且属于本人同一账号的会员会话；不能仅检测 Cookie 存在就宣布成功。
- 分别验证消费者账号、包裹/取件码、身份码材料三项读取能力。现有淘宝 H5 会话可查不带 .cn 的包裹；菜鸟身份 Cookie 可更新材料，两者不可混用。
- 必须验证冷启动、会话到期后的 refreshToken 续登、令牌轮换、材料续期、明确失效后重新登录，才替换人工导入流程。
- 临时安装官方菜鸟并由本人登录可用于恢复新会话和观察一次正常链路；这仍是诊断/恢复手段，不等于独立登录完成，安装须先取得用户确认。

## 证据与恢复位置

保留 APK：outputs/cainiao-8.11.1023.apk。临时 JADX 1.5.6 和选择性源码位于 /tmp/.agents/expressassistant-identity-refresh/；临时文件可能被清理，可以从保留 APK 重建。正式结论以本文为准，不发布反编译全文、签名、会话或个人订单。

本人私有旧会话、材料保存在 outputs/.private/cainiao-runtime/，只供授权续接；原始日志/屏幕与实际码观察文件结束前清理。正式 App 凭证仍使用 AndroidKeyStore/noBackup 加密存储。

## 2026-11-06 本人官方登录后的实测

- 用户明确允许临时安装官方菜鸟8.11.1023到Redmi K70，并亲自在官方提示与短信流程中完成登录、打开身份码；未安装其它辅助组件。只读取该App进程的任务临时日志，提取最小会话后停止采集并清理原始日志/屏幕。
- 实际观察到正常顺序：mtop.taobao.mloginservice.smsSend → smsLogin → mtop.cainiao.cnmember.customer.login。短信与会员登录使用 cn-acs.m.cainiao.com/gw 原生网关，会员登录数据根字段为 loginRequest。这次补上运行证据，不再只有静态接口名称。
- 登录成功日志确实包含消费者tenantId=78的SessionInfo，带sessionId、refreshToken、cnAccountId、ext.sessionIdExpired和ext.refreshTokenExpired。仅最小会员对象保存到outputs/.private/cainiao-runtime/member-session-private.json（0600，不进Git/Release），没有把它接入正式程序的自动续登。
- 本次sessionIdExpired为2026-11-07 01:54:27.894、refreshTokenExpired为2026-12-06 01:54:27.894，America/Cuiaba（UTC-04:00）；相对登录约1天和30天。新身份材料到期约2026-11-21，即另一个15天期限。仅代表本次响应，未验证续登后是否滑动延长。
- 新Cookie在既有H5路径成功更新离线材料，验证userIndex与旧材料一致且期限向前；不复用捕获的原生x-sign。H5在线identitycode.get.cn同样SUCCESS、20字符。
- 经手机维护API只导入新的cainiao部分，202条快递和JD/TB/PDD各1账号保持；POST /api/identity/online/refresh与/api/identity/refresh均200，source=online、needsLogin=false、错误清除。手机实际点击刷新使码变化；CODE128解码与屏幕一致，新旧在线码均反解为同一原离线账号。在线不同请求不要求返回同一码。
- 用新有效Cookie复核customer.autologin的H5预检：12574478、23050477两个appKey均FAIL_SYS_UNAUTHORIZED_ENTRANCE。存在已有H5 token也不改变入口拒绝。没有发送真实refreshToken尝试轮换，未破坏官方App现有会员会话。
- 尚缺独立消费者短信登录的受支持入口/原生环境、会员转换与自动续登。当前保存会员token是研究材料，不是正式App已具备长期免登录。正式身份材料与Cookie仍加密保存在快递助手中。

## 2026-11-09 最小运行时研究（未替换正式依赖）

用户允许研究将必要组件集成到 APK，以去掉完整官方 App 安装要求。静态检查本人保留的 8.11.1023 arm64 APK：

- 当前调用面为 SecurityGuardManager、IStaticDataStoreComponent、IUnifiedSecurityComponent；登录 SDK 只额外读取 SDK_VERSION。完整 UI、推送和订单页面并非当前代码直接调用的依赖。
- libsgmain.so、libsgmiddletier.so、libsgsecuritybody.so 实际是含 classes.dex、AndroidManifest、资源表和 CERT.RSA/SF 的签名 ZIP 插件，不是普通 ELF。另有对应 6.6.230703 原生实现、版本标记和 ucb 文件。
- 三组插件/原生实现加 yw_1222.jpg 与 yw_1222_mwua.jpg 约 3.4 MiB；这不是最终 APK 增量，未计 Java API、传递依赖、打包压缩与其它架构。不能只复制三个文件。
- 正式加载器目前依赖 createPackageContext(com.cainiao.wireless) 的 classLoader、资源和 ApplicationInfo，并校验官方安装证书。这个证书检查是本项目的检查，不能据此声称已经证明 SDK 自身必定拒绝第三方包名/签名。
- 尚未验证裁剪后的完整依赖闭包、独立资源上下文、原生初始化、冷启动，以及真实签名和会员续登。没有把官方代码复制进公开仓库或 Release，也没有卸载手机官方 App。
- 后续实验应在项目隔离构建中保留插件原始字节，先验证独立加载，再验证真实只读查询及续登；成功标准必须是不通过官方已安装包获取代码/资源。正式替换前还需明确组件分发条件。

减少服务依赖与减少安装依赖分开处理：手动运单已有 KuaiDi100 查询路径，本轮补入正常同步；JD/PDD/TB 各自提供订单/轨迹。菜鸟取件码、身份码与会员续登仍使用菜鸟服务，本轮未取得可替代这些服务的证据。公开实现搜索未返回可用的最小运行时实现，不能据此认定不存在。
