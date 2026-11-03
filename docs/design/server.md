# PC 服务器版设计（server/）

## 边界

- 用户要求（2026-11-03）：手机端不再提供 AI 接口；PC 做成服务器版，**用和手机一样的方式独立抓取**，提供 AI 读取接口，并让其他设备用网页查看。取件码是最重要的信息。
- 服务器不依赖手机：自己保存四个渠道的登录态，自己同步。手机 App 与服务器之间没有数据通道。

## 抓取方式（与 App 对应）

| 渠道 | App | 服务器 |
| --- | --- | --- |
| 小米 | OkHttp + MIUI CoderUtils/AES | aiohttp + 同款 AES 兜底；oaid/vaid 每账号随机生成并持久化；serviceToken 失效时用 passToken 重新 mint |
| 淘宝 | mtop wapSign + SSR 物流页 | 同样纯 HTTP；Cookie 从 Playwright storage_state 取 `taobao.com/tmall.com` 域 |
| 京东 | 隐藏 WebView + JSONP 回调改写 | Playwright 页面 + `page.on("response")` 直接读 `order_list_m` 响应（不需要改写回调）；轨迹读 deal_wuliu 页 DOM |
| 拼多多 | 隐藏 WebView + XHR/fetch 钩子 | Playwright 读 `proxy/api` 响应；列表用 CDP 触摸滑动翻页；轨迹点「查看物流」后解析 DOM |

- 不要在网络层自己重放京东/拼多多的签名请求（h5st / anti_content 无法自算，且会被风控），一律让页面自己发请求、浏览器里读响应。
- 京东/拼多多用移动端 context（Android UA、触摸、400×860）；小米登录用桌面 UA（与 App 一致）。

## 登录

- 登录与后台同步分开选择是否显示窗口。`--login-mode auto` 默认在可用桌面且 HTTP 客户端是回环地址时打开真实的 Chrome/Edge 窗口，网页只轮询完成状态，不请求截图或转发输入。登录成功先保存 storage_state，再关闭窗口。手动关闭窗口视为取消，不覆盖已有账号。
- 有桌面时网页始终提供明确的「本机浏览器登录」按钮，远程客户端也可以让服务器电脑打开窗口。Windows 用进程窗口站的可见标志判断桌面，macOS 检查 console 用户，Linux 检查 DISPLAY/WAYLAND_DISPLAY。无桌面时拒绝 local，提供 remote 和 Cookie 导入。
- 远程画面仍为无头浏览器 + JPEG 轮询（`scale=css`，截图像素 = CSS 像素），点击/文字/按键/滚动/拖动转发给 Playwright。未改成实时视频推流。Cookie 导入支持请求头、Cookie-Editor JSON 与 storage_state，仅保留对应平台的 Cookie 域。
- BrowserManager 分别缓存 headed/headless 进程；账号 context 仍然独立；后台同步不受登录模式影响。默认端口为 18765，避开本机 8765 已有服务。
- 每 2 秒检查 Cookie 判定登录成功：京东 `pt_key+pt_pin`；淘宝 `cookie2+(unb|cookie1)`；拼多多 `PDDAccessToken`；小米 `passToken+userId+deviceId` 后 mint。
- 登录态存 `data/profiles/<channel>-<账号id>.json`（storage_state）；同步后回写，保持会话续期。同一账号（key：pt_pin / unb / pdd_user_id / userId）重复登录只更新凭证。

## 合并与取件码

- 合并顺序与 App 相同：小米 > 京东 > 淘宝 > 拼多多；渠道失败或 0 件保留旧数据；拼多多与历史取并集；未启用渠道的件不残留；只同步部分渠道时其余渠道原样保留。
- 轨迹：小米列表自带 details、淘宝 SSR 自带轨迹，直接入库；京东/拼多多在途件每次同步补抓（30 分钟 TTL，每账号最多 25 件），已完成件只在详情页手动刷新时抓。
- 取件码：先看最新轨迹文本，再从轨迹最新一条往前找；正则与 App 一致并补「取件密码」。已签收件不展示取件码。
- 拼多多列表若状态仅有「拼过 N 次」等营销文案，最新轨迹明确已取走/已完成取件，或无驿站/代收提示的「已签收」，优先归完成；驿站代收仍为待取件。
- 分区在 App 的基础上最前面加「待取件」：未完成且有取件码，或最新轨迹提到驿站/快递柜/待取件。

## 接口

- 除 `/api/health` 与静态页外全部要求 Bearer 令牌（首次启动生成，存 `data/config.json`）。
- MCP 只实现 Streamable HTTP 的 JSON 响应模式（initialize / ping / tools/list / tools/call，通知返回 202），工具只读，外加 `sync`。

## 未验证

- 2026-11-03 已在真实账号登录与同步中取得淘宝 6 件、拼多多 153 件；用户完成本机京东登录后，完整会话迁入正式 data，再次成功同步京东 17 件，合并历史数据共 176 件。小米尚未验收。
- 小米接口对非真机 oaid/vaid 与环境信号是否放行未知。
- Windows 的桌面检测与登录态跨机器复用尚未在 ROG 实机验收。ROG 部署时迁移完整 data（配置、账号 ID、Cookie、origins），不要只复制零散 Cookie。跨机器可能触发平台验证。

## 打包

- wheel 只包含 express_server；sdist 用明确的 only-include 白名单包含代码、测试、README、pyproject 与锁文件。以 server/ 为构建根时，不要假设仓库根 .gitignore 的 server/data/ 能排除源码包里的 data/。
- 发布前检查归档成员：必须含网页 index.html，不能含 data/、.venv/、运行日志或 profiles。账号迁移通过受控文件传输完成，不通过 Git/Release。

## 菜鸟身份码探测

- 公开实现的调用参数参考：`polygongfw/pickup-assistant` 的 `data/Api.kt` 和 `data/Mtop.kt`，只参考接口名与参数，没有复制其源码。
- 真实淘宝会话调用 `mtop.cainiao.nbpickup.identitycode.get` 1.0，data=`{"appName":"GUOGUO","verifyVersion":"V2"}`，得到 `SUCCESS`，但 `data.model` 仅有 `certificated`、`hasNewCertificated`、`simpleCertificated`、`weakCertificated`、`xnCertificated`，均为字符串 `false`，无二维码内容。
- 这说明该会话尚未取到可用身份码，不能据此断言接口已接通完整功能或二维码已可出示。下一步需用户在官方入口检查身份码开通/认证状态，之后再验证码字段和有效期。不要猜测字段并生成误导性的二维码。
- 补充探测（同日）：`verifyVersion` 换 V1 或不传，结果相同（五个 false）。公开项目 `pickup-assistant` 只定义了身份码接口名，界面并未实现身份码出示；GitHub 代码搜索未找到其他实现。结论：身份码依赖账号先在淘宝 App 官方入口完成开通/认证，开通前无法取得码内容。

## 菜鸟「我的包裹」取件码（候选数据源，未接入）

- `mtop.cainiao.lpc.packageservice.queryPdsPackageDataList` 1.0（备份 `mtop.cainiao.pcs.packageservice.queryPackageDataList`），data=`{"pageSize":50,"currentPage":N,"needTemporality":false,"needDynInfo":"{'needFindTemporality':true,'needAppointment':true}"}`，按 `totalPage` 翻页；淘宝会话 + 现有 mtop 签名即可调用。
- 真实会话返回 13 件，包含京东、闲鱼等「末端非淘包裹」：按收件手机号覆盖所有进菜鸟驿站的件，不限淘宝订单。
- 取件码字段：`packageStation.authCode`；值为占位 `needSms` 时回退 `feature.stationOrderPickUpCode`（feature 是 JSON 字符串，也可能有 `featureObj`）；两者都没有则表示需短信验证，不能当码展示。当前 13 件全部已签收，authCode 均为 `needSms` 或空，待取件状态下的真实码尚未验证。
- 菜鸟物流轨迹文本只写「请凭取货码及时取件（查询方式：短信/物流详情页）」，不含码，所以靠轨迹正则拿不到菜鸟驿站的取件码；这个接口是驿站件取件码的主要来源。
