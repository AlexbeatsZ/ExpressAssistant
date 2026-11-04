# 菜鸟手机号登录可行性（2026-11-04）

## 结论

可以继续实现，最有希望的是在程序内打开官方短信登录页，登录后保存本人会话，接上已有查询和身份材料续期逻辑。尚未验证该网页取得的会话是否有消费者菜鸟 App 的全部权限，不能把找到登录页或 API 名称当成全流程成功。

目前只读取公开网页和本人已有安装包，没有发送短信、提交验证码、绑定账号、签协议或注销当前登录态。

## 当前证据

### 官方网页

实际 GET `https://cnlogin.cainiao.com/` 跳转 `/login`，返回 200，标题「菜鸟统一登录」。页面的 `window.viewConfig.api` 明确给出：

| 功能 | 页面配置中的路径 |
| --- | --- |
| 发短信 | `/sms/send?appName=cainiao&fromSite=21` |
| 短信登录 | `/sms/login?appName=cainiao&fromSite=21` |
| 短信注册 | `/sms/reg?appName=cainiao&fromSite=21` |

页面配置 `useBaxiaNc=true`，加载 AWSC/Baxia 脚本，说明需保留官方验证交互。不要把这些路径直接做成绕过页面验证的裸请求。注册是独立操作，本项目的登录不能悄悄改成注册或绑定。

只读查看的公开前端为 `cn-member-react/cnmember-register/0.0.79/js/pc-login.js`，其中使用短信登录、短信发送和登录重定向。此网页名称是统一登录；消费者账号、业务授权与 Cookie 可用范围还要实测，不能仅凭域名判断。

### Android 8.11.1023

`CnmcDefaultAppProvider` 继承淘宝统一登录数据提供者，`isSmsLoginPriority()` 为 true，`supportPwdLogin()` 为 false，`getSite()` 取 CAINIAO_C tenant。

`UserLoginServiceImpl` 构造了：

- `mtop.taobao.mloginService.smsSend` 1.0。
- `mtop.taobao.mloginService.smsLogin` 1.0。

登录请求除手机号、验证码外，还带 smsSid、site、appName、deviceId、utdid、ttid、sdkVersion、时间及 `riskControlInfo`；可选设备令牌及签名。MTOP SDK 负责封装传输。静态字段不能证明某项可以省略，也不能证明所有调用都需要已有 SESSION；登录本来就是取得会话的过程。

菜鸟会员 SDK 又有一层：

- `CnMemberLoginServiceImpl.login` 把含 `havanaSsoToken` 的 LoginRequest 发到 `mtop.cainiao.cnmember.customer.login`。
- 该请求类声明 NEED_SESSION=false、NEED_ECODE=false；这不等于不需要网关签名或环境验证。
- `CnMemberLoginServiceImpl.autoLogin` 发 `mtop.cainiao.cnmember.customer.autologin`，AutoLoginRequest 有 accountId、refreshToken。
- 另有旧转换接口 `mtop.cainiao.cboss.cnaccount.convert.havana`，不能在没有运行证据时假定它仍是当前主路径。

因此完整链路大致是：官方手机号验证 → 阿里登录会话/SSO → 菜鸟会员会话 → 获取包裹或身份材料；续登有独立请求。材料到期与登录态到期不是同一概念，refreshToken 的实际寿命和轮换规则尚未测量。

`CustomerAppProvider.getAuthSDKInfo()` 从 SecurityGuard 静态存储读取 ONEKEY_LOGIN_KEY，针对运营商一键登录。不要把这一发现直接当成短信登录必定无法实现的证据，也不要把别的应用的认证材料复制进本程序。

## 实现顺序

1. 新增独立的官方网页登录会话，复用现有 WebView / Playwright 登录机制。用户在官方页面输入手机号、点击发送短信、输入验证码并完成人机验证；程序不代发短信、不接管验证码，不添加额外组件。
2. 登录成功后只保存对应账号的 Cookie/storage_state，沿用当前本地凭证保护方式。手机与服务器各自保存自己的会话；不能假设两者自动共享。
3. 对新会话分别验证三个读取能力：消费者本人基本账号、包裹列表及取件码、`native.identitycode.get.cn` 材料。单项成功不代表另外两项成功。
4. 如得到的是阿里会话而非菜鸟业务会话，研究页面正常 SSO 跳转及会话转换。若要求绑定或协议，必须呈现官方流程给用户。
5. 验证会话保存、应用重启、普通续期、身份材料续期和失效后重新登录；只有完成这些才把手机中当前的人工导入流程替换掉。

直接实现原生 MTOP 短信流程也可以研究，但工作量更大，存在 SDK 初始化、签名与风控兼容问题。当前优先验证官方网页会话范围；本轮不把未验证的登录按钮塞进正式版。

## 已有会话的实测边界

本人菜鸟 App 的 H5 Cookie 已用于身份材料续期成功；同一 Cookie 用于包裹接口却没有成功。淘宝 H5 会话可以查询不带 `.cn` 的菜鸟包裹接口。因此后续登录必须按业务验证，不能只检测 Cookie 存在就宣布登录成功。

## 证据位置

临时分析目录 `/tmp/.agents/expressassistant-ui-cleanup/` 的 `cnlogin-public.html`、`cnlogin-public.js` 与 `login-java/` 为只读分析材料；不含新登录凭证。公开文档仅记录接口名与结论，不提交 APK 反编译全文或私人订单。现有私人会话、身份密钥和账号令牌不进入本说明。
