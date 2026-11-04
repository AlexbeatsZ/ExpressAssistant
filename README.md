# 快递助手 · ExpressAssistant

Android 快递聚合工具：在一个程序里查看快递、取件码和菜鸟身份条形码。电脑服务器独立保存账号与同步数据，提供 REST / MCP 查询和操作接口。手机与服务器均不内置模型、AI 对话或日报。

当前维护仓库：[AlexbeatsZ/ExpressAssistant](https://github.com/AlexbeatsZ/ExpressAssistant)，基于 [Halo0sama/ExpressAssistant](https://github.com/Halo0sama/ExpressAssistant)。

## 手机功能

- 聚合小米、京东、淘宝、拼多多及菜鸟包裹，保留平台账号独立配置。
- 底部「快递 / 身份码」导航；顶部搜索商品、公司、单号或取件码，搜索包含已完成记录。
- 在搜索框输入运单号自动识别承运商，点击添加或提交搜索即可保存；相同单号不重复添加。
- 完成和异常记录从工具栏打开，未知状态显示待更新；有明确派送信号才显示派送中。
- 物流轨迹、预计送达、取件码、跟踪通知、快递日历和桌面小组件。
- 菜鸟身份码使用 CODE_128，身份材料与本人会话加密存储并支持续期；初始化通过本机维护 API。
- 可选快递100查询和无障碍导入；不依赖额外测试组件。

## 使用

1. 在设置中登录需要的平台账号，回到快递页下拉同步。
2. 搜索框可以查历史，也可以识别并添加运单号；长按卡片可改名、跟踪或移除。
3. 从底部右侧打开身份码。当前身份会话初始化与维护接口见 [API 说明](server/AI_API.md)。独立手机号登录仍在研究，见 [可行性记录](docs/design/cainiao-login.md)。
4. 需要外部程序管理时，使用电脑服务器的查询和操作接口；日常调用不要求手机在线。

## 电脑服务器

[server/README.md](server/README.md) 介绍独立登录、同步、网页维护和运行方式。[server/AI_API.md](server/AI_API.md) 给出 REST、MCP、鉴权、状态字段、同步结果判断和手机维护接口。公开说明不含真实令牌。

## 构建

需要 JDK 17、Android SDK 36。构建与检查：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

个人签名配置放在本地 `.signing/personal.properties` 与对应 keystore，均不进入 Git。未提供私有签名时可使用 `:app:assembleDebug`。

## 数据

手机账号和快递保存在手机应用目录；菜鸟身份会话使用 AndroidKeyStore 加密。服务器在自己的 `server/data/` 保存账号、浏览器会话、快递和访问令牌。两端独立运行，不自动交换账号或快递。联网查询仍会向所选平台发送必要请求。

## 许可

保留上游 MIT 许可及署名，见 [LICENSE](LICENSE)。本项目与各快递或电商平台无隶属关系，第三方接口可能变化。
