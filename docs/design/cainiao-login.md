# 菜鸟消费者直接登录研究

## 当前结论（2026-11-06）

独立手机号登录尚未实现。已还原消费者 App 的短信、SSO 转会员及会员续登链路，但没有验证一份由本程序独立登录得到的消费者会话。正式版不放置不能完成登录的入口。

此前将 cnlogin.cainiao.com 作为首选的判断需要修正：其公开网页固定使用商家账号域 21/cainiao，消费者 App 使用 78/cainiao2c。存在短信表单不能证明登录后可取得消费者身份码。

早期探测只做公开网页读取、本人保留APK的静态分析和无手机号负面对照。后续用户明确允许临时安装官方菜鸟，并亲自在官方App完成短信登录、打开身份码；程序没有代发短信或接管验证码。当前已恢复本人同一账号的新会话，独立登录与自动续登仍未实现。

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
