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

- 服务器多为无显示器环境（Windows 服务会话），登录走「远程画面」：无头浏览器打开登录页，网页轮询 JPEG 截图（`scale=css`，截图像素 = CSS 像素），点击/文字/按键/滚动/拖动转发给 Playwright。
- 每 2 秒检查 Cookie 判定登录成功：京东 `pt_key+pt_pin`；淘宝 `cookie2+(unb|cookie1)`；拼多多 `PDDAccessToken`；小米 `passToken+userId+deviceId` 后 mint。
- 登录态存 `data/profiles/<channel>-<账号id>.json`（storage_state）；同步后回写，保持会话续期。同一账号（key：pt_pin / unb / pdd_user_id / userId）重复登录只更新凭证。

## 合并与取件码

- 合并顺序与 App 相同：小米 > 京东 > 淘宝 > 拼多多；渠道失败或 0 件保留旧数据；拼多多与历史取并集；未启用渠道的件不残留；只同步部分渠道时其余渠道原样保留。
- 轨迹：小米列表自带 details、淘宝 SSR 自带轨迹，直接入库；京东/拼多多在途件每次同步补抓（30 分钟 TTL，每账号最多 25 件），已完成件只在详情页手动刷新时抓。
- 取件码：先看最新轨迹文本，再从轨迹最新一条往前找；正则与 App 一致并补「取件密码」。已签收件不展示取件码。
- 分区在 App 的基础上最前面加「待取件」：未完成且有取件码，或最新轨迹提到驿站/快递柜/待取件。

## 接口

- 除 `/api/health` 与静态页外全部要求 Bearer 令牌（首次启动生成，存 `data/config.json`）。
- MCP 只实现 Streamable HTTP 的 JSON 响应模式（initialize / ping / tools/list / tools/call，通知返回 202），工具只读，外加 `sync`。

## 未验证

- 四个渠道的真实账号登录与抓取尚未用真实账号验收；字段解析沿用 App 已验证的逻辑，并用合成数据做了单元测试。
- 小米接口对非真机 oaid/vaid 与环境信号是否放行未知。
