# 快递助手 · PC 服务器版

在电脑或家里的服务器上常驻运行：**自己登录并抓取**小米 / 京东 / 淘宝 / 拼多多的快递，网页集中显示**取件码**，局域网（或 Tailscale）里的手机、平板、电脑用浏览器就能看；同时提供 REST 和 MCP 接口，供 AI 读取。

不需要手机 App 在线，也不需要装淘宝 / 拼多多 App：和 App 一样走各平台的手机网页版（H5）。小程序里下的单也能看到，前提是网页登录的账号和小程序是同一个账号（拼多多小程序常用微信登录，网页用手机号登录，需要该手机号已绑定在同一个拼多多账号上）。

## 运行

需要 [uv](https://docs.astral.sh/uv/) 和一个 Chromium 内核浏览器（Windows 自带的 Edge 即可；macOS 可用 Chrome）。

```bash
cd server
uv run express-server
```

首次启动会生成访问令牌并打印出来，同时显示局域网地址。常用参数：

| 参数 | 说明 |
| --- | --- |
| `--host 0.0.0.0` | 默认监听所有网卡；只给本机用填 `127.0.0.1` |
| `--port 8765` | 端口 |
| `--data ./data` | 数据目录（账号登录态、快递、轨迹、令牌），默认 `server/data/` |
| `--token xxx` | 指定访问令牌 |
| `--browser msedge` | 指定浏览器；默认依次尝试 Edge、Chrome、Playwright 自带 Chromium |
| `--headed` | 显示浏览器窗口（调试用） |

没有 Edge/Chrome 时，运行 `uv run playwright install chromium` 安装 Playwright 自带的浏览器。

## 登录各平台

打开网页 → 右上角「账号」→「+ 登录 X 账号」。服务器在无头浏览器里打开平台登录页，画面实时显示在网页里：点画面就是点击，下方输入框发送文字，拖动用于滑块验证。登录成功后自动保存登录态。

- 京东 / 淘宝 / 拼多多：手机号 + 短信验证码最省事。
- 小米：用小米账号登录，之后在账号里填**收件手机号**（小米按手机号查件；手机号需要已在小米智能助理里绑定过）。

登录态失效时，账号下方会显示错误，点「重新登录」即可，同一账号不会重复添加。

## 网页

- 默认打开「待取件」：取件码用大号字显示；点卡片看完整物流轨迹，可在线刷新。
- 自动同步默认每 30 分钟一次（账号页可改，0 = 关闭）；在途件每次同步时补抓轨迹，用来识别取件码。

## AI 接口

除 `/api/health` 外都需要请求头 `Authorization: Bearer <令牌>`。

### MCP（Streamable HTTP）

地址 `http://<服务器>:8765/mcp`。工具：

| 工具 | 作用 |
| --- | --- |
| `pickup_codes` | 待取件的快递和取件码 |
| `list_packages` | 按分区（pickup / delivering / shipped / notshipped / abnormal / done / active / all）或关键字列出 |
| `package_detail` | 单个快递的完整轨迹，可选在线刷新 |
| `summary` | 一段文字概括全部快递 |
| `sync` | 立即同步 |

Claude Code 示例：

```bash
claude mcp add --transport http express http://<服务器>:8765/mcp --header "Authorization: Bearer <令牌>"
```

### REST

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（无需令牌） |
| GET | `/api/pickup` | 待取件列表 |
| GET | `/api/express?section=&q=` | 快递列表 |
| GET | `/api/express/{单号}?refresh=1` | 详情与轨迹 |
| GET | `/api/summary` | 文字概括 |
| GET / POST | `/api/sync` | 同步状态 / 触发同步（`{"wait": true}` 等待完成） |

## 安全

- 令牌等同于查看你全部快递的权限；不要把服务直接暴露到公网，建议只在局域网或 Tailscale 内访问。
- 数据目录里有各平台的登录 Cookie，只保存在这台机器上。
