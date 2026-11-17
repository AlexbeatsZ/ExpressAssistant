# 快递助手 · ExpressAssistant

Android 快递聚合工具：在一个程序里查看快递、取件码和菜鸟身份条形码。电脑服务器独立保存账号与同步数据，提供 REST / MCP 查询和操作接口。手机与服务器均不内置模型、AI 对话或日报。

当前维护仓库：[AlexbeatsZ/ExpressAssistant](https://github.com/AlexbeatsZ/ExpressAssistant)，基于 [Halo0sama/ExpressAssistant](https://github.com/Halo0sama/ExpressAssistant)。

## 手机功能

- 聚合小米、京东、淘宝、拼多多及菜鸟包裹，保留平台账号独立配置。
- 底部「快递 / 身份码」导航；顶部搜索商品、公司、单号或取件码，搜索包含已完成记录。
- 在搜索框输入运单号自动识别承运商，点击添加或提交搜索即可保存；相同单号不重复添加。
- 完成和异常记录从工具栏打开，未知状态显示待更新；有明确派送信号才显示派送中。
- 物流轨迹、预计送达、取件码、跟踪通知、快递日历和桌面小组件。
- 菜鸟运行环境与手机号登录入口集成在同一个 APK 内，不需要另装菜鸟或模块管理器；设置中的菜鸟登录直接接收 SDK 认证结果。
- 菜鸟身份码使用 CODE_128，身份材料与本人会话加密存储并支持原生会员续登；本机维护 API 继续可用。
- 可选快递100查询和无障碍导入；不依赖额外测试组件。

## 使用

1. 在设置中登录需要的平台账号，回到快递页下拉同步。
2. 搜索框可以查历史，也可以识别并添加运单号；长按卡片可改名、跟踪或移除。
3. 菜鸟账号入口位于「设置 → 账号与同步 → 菜鸟登录」，身份码从底部右侧打开。整合包已通过原版停用后的原生续登与身份码刷新，包内登录已由用户在手机完成验证，详见 [登录记录](docs/design/cainiao-login.md)。维护接口见 [API 说明](server/AI_API.md)。
4. 需要外部程序管理时，使用电脑服务器的查询和操作接口；日常调用不要求手机在线。

## 电脑服务器

[server/README.md](server/README.md) 介绍独立登录、同步、网页维护和运行方式。[server/AI_API.md](server/AI_API.md) 给出 REST、MCP、鉴权、状态字段、同步结果判断和手机维护接口。公开说明不含真实令牌。

## 构建

界面模块需要 JDK 17 和 Android SDK 36。构建与检查：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

整合交付还需要已有的本地运行时 APK 输入与 JDK 21+，继续执行 [单包构建流程](tools/cainiao-host/README.md)。最终交付是 `outputs/ExpressAssistant-v<版本>.apk`，包名和个人签名保持不变。构建程序不会下载或安装独立菜鸟。

个人签名配置放在本地 `.signing/personal.properties` 与对应 keystore，均不进入 Git。`:app:assembleDebug` 与 CI 附件仅用于界面模块开发，不是完整的菜鸟集成交付包。内置宿主和构建工具的来源见 [设计记录](docs/design/cainiao-embedded.md)。

## 数据

手机账号和快递保存在手机应用目录；菜鸟身份会话使用 AndroidKeyStore 加密。服务器在自己的 `server/data/` 保存账号、浏览器会话、快递和访问令牌。两端独立运行，不自动交换账号或快递。联网查询仍会向所选平台发送必要请求。

## 许可

保留上游 MIT 许可及署名，见 [LICENSE](LICENSE)。本项目与各快递或电商平台无隶属关系，第三方接口可能变化。
