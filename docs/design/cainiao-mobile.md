# 手机菜鸟身份码与取件码

## 范围

2026-11-04 用户要求部署手机；服务器只需快递、取件码 API，不新增前端或身份码功能。手机保留四源聚合，增加本人菜鸟离线身份码（CODE_128 条形码）及材料续期。手机与服务器各自同步，不依赖 ADB 常连。

## 会话边界

- 身份码使用用户明确授权取得的菜鸟 App Cookie，H5 `native.identitycode.get.cn` 可续期；不需要重放原生 x-sign。
- 菜鸟「我的包裹」使用淘宝 H5 会话和不带 `.cn` 的列表 API。实测上述菜鸟 App Cookie 不能直接用于列表，不能混用。
- 服务器旧淘宝身份码接口的五个 false 只表示该请求上下文未下发码，不证明本人未实名或未授权。

## 材料与算法

- 私有 Cookie 与材料通过系统文件选择器一次性导入，使用 AndroidKeyStore AES/GCM 存到 noBackupFilesDir；不进 APK、Git 或 Release，不提供 HTTP 导出。
- 服务端 expireTime 是材料缓存绝对毫秒到期；登录会话寿命是另一回事。打开身份码页及每 12 小时的 WorkManager 在剩余不足一天时尝试续期。系统可延迟后台工作；打开页面兜底。失败保留有效旧材料，过期后停止出码。
- 服务端 codeType/period/digits 驱动算法；counter 先转八字节大端的十六进制小写文本再 HMAC-SHA1，不能直接用标准 TOTP。按官方交换十位索引后拼 CS8 + 索引 + OTP + codeType。
- 续期必须验证完整材料、同一 userIndex、到期时间不倒退，再原子替换。错误不记录响应体、Cookie、密钥或实际码。
- 本地生成与条形码可解码不等于驿站扫码成功，实体站点验收尚未完成。

## 包裹

- 手机随淘宝同步独立查询 PDS 列表，失败回退 PCS，按 totalPage 翻页。失败保留旧菜鸟件，不影响其他渠道。
- authCode 优先，只有 needSms 才查 feature.stationOrderPickUpCode。占位、空、终态不显示码；不跳过短信核验。
- 按 mailNo 补已有件取件码与站点，额外未完成件入列；成功返回空码时清除同件旧码，避免把历史码继续当成当前码。

## 手机本机管理 API

- 用户追加要求部署/导入可由 API 操作。复用历史 NanoHTTPD 2.3.1 依赖，以新 PhoneControl 实现 127.0.0.1:18768，独立随机 Bearer token 保存在 noBackupFilesDir。与服务器 18765 的 AI API 分开。
- App 进程启动时监听；系统杀进程后需重新打开 App。它用于手机维护，不承诺后台常驻服务；服务器 API 仍独立运行。
- /api/setup 接收带 Content-Length 的 JSON（最多 4 MB），直接读内存，不经共享目录/HTTP 临时文件；错误统一返回短消息，不能打印包含 Cookie 的 JSON 异常。
- /api/status 只返回材料期限、周期、码长度、来源、换码时间与固定错误，不导出 Cookie、密钥或实际身份码；/api/identity/refresh 触发本人材料续期；/api/identity/online/refresh 请求在线码；/api/express、/api/pickup 读缓存；/api/sync 触发异步同步、GET 查结果。
- 电脑通过 ADB forward 访问；手机身份码页可复制维护配置。配置含敏感令牌，不进公开文档。最终只安装主 APK，无需单独测试组件。

## 部署

- 自有 fork 使用项目私有签名（.signing/，Git 排除），保留该密钥以便后续覆盖升级。上游签名与本 fork 不通用。
- 初始化包可包含现有账号 Cookie、快递与商品缓存。由本机鉴权 API 或显式选择文件导入；导入前完整解析验证；导入后清除临时明文副本。
- 服务器继续使用现有 Mac 18765 服务及 data，不迁移或部署到 ROG。API 说明见 server/AI_API.md。

## v0.9.3 刷新与失效（2026-11-06）

- 本轮已确认 Cookie 失效：FAIL_SYS_SESSION_EXPIRED；离线材料仍有效。实际跨过 600 秒周期条形码变化且独立生成器匹配，自动换码未停止。一次材料续期成功不能保证 Cookie 有长期续登能力。
- 身份页打开及点击刷新先请求 identitycode.get.cn 在线码；有效在线码优先，超时后回退未过期的离线材料。在线接口拒绝时可尝试已有成功路径的材料更新；手动按钮不会伪造同一离线周期内的新码。
- 在线 expireTime 是相对秒数，与材料绝对毫秒期限分开解析。有效在线缓存加密保存在同一会话内，API 不导出实际码。
- 显示 MM:SS 换码倒计时；固定错误明确区分登录失效、未登录、网络、网关拒绝、响应无效。会话失效后停止页面每分钟自动重试，手动刷新仍可重试；失败保存错误元数据并保留有效缓存。
- 手机 API 登录失效/未登录返回 409 + CAINIAO_LOGIN_EXPIRED/CAINIAO_NO_SESSION；网络等返回 503；手机 Bearer 认证错误仍为 401。响应仅固定短消息与身份状态。
- 本轮 K70 0.9.3/17 已安装，两个刷新端点均验证 409 和 needsLogin=true，现有条码保留；202 件、三个平台账号仍在。独立消费者登录未实现、有效新会话的在线码成功路径尚未在本版实机验收。后台 WorkManager 仍沿原有材料续期策略。
