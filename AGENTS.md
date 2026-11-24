# ExpressAssistant（AlexbeatsZ fork）

## Goal

- Android 快递聚合 App（上游 Halo0sama/ExpressAssistant）。用户 fork 后的方向：
  - 手机端聚合快递、取件码、菜鸟身份条形码。旧 8765 REST/MCP/CLI 已删除；2026-11-04 按用户追加要求提供 127.0.0.1:18768 本机维护 API（独立 Bearer、导入/状态/同步/续期），与服务器 AI API 分开；无额外测试组件依赖。
  - PC 服务器版 `server/`：独立登录并抓取小米/京东/淘宝/拼多多，网页查看（其他设备浏览器访问），REST + MCP 给 AI 读取。**取件码是最重要的信息**。
  - 菜鸟：包裹列表沿用淘宝 H5 会话、不带 .cn 的 API。本人菜鸟 App Cookie 可在 H5 调 native.identitycode.get.cn 续期，手机已实测成功；这份 Cookie 不能直接用于包裹列表。旧淘宝身份接口五个 false 不能证明未实名/未授权。

## Current State

- 2026-11-24：ROG 源码已迁至 `C:\Users\Meta\source\repos\ExpressAssistant`，持久数据为 `%LOCALAPPDATA%/ExpressAssistantServer/data`；原 SYSTEM 任务、令牌和登录态保留。合并手机端最新记录时保留了迁移启动/停止脚本；服务器执行代码未变，原进程未重启，health200/199条记录。Windows 开机触发尚未重启验收。

- 2026-11-22：v0.9.18/code32已按用户确认的Pixel样式完成登录失效提醒：按账号持久保存明确认证失效，主页提醒卡、设置摘要和账号卡重新登录联动，网络/超时不误报；稳定账号身份与登录代次隔离迟到请求。82单测、lintRelease零错误（470警告/1提示）、隔离Android16模拟器明暗色各3项集成测试通过。个人签名整合APK已覆盖K70并再次冷启动，升级前41件及四平台各1账号全部保留；真实同步无错误、菜鸟在线刷新200，目前无已确认失效提醒。失效/重登恢复在隔离测试账号验证，未故意破坏手机登录。main已推送，v0.9.18正式Release已发布并设Latest，附件172794432字节，与手机安装包相同。最终交付outputs/ExpressAssistant-v0.9.18.apk，私有备份及脱敏验收在outputs/.private/login-status-20261122/。

- 2026-11-17：0.9.17/code31改为一次显示一个身份码，卡片下方独立按钮行，刷新左侧「切换」直接切换菜鸟/淘宝，本机保存上次平台并在冷启动恢复。卡片与按钮整体在内容区域居中偏上48dp，首次默认菜鸟。只绘制/刷新当前平台，切换取消旧请求并清除旧条码视图。用户反馈部分机器多码同时显示出错，具体机型和根因尚未复现。79单测、lintRelease零错误（465警告/1提示）和签名整合构建通过；K70已覆盖，冷启动与升级保留淘宝选择，用户确认切换显示正常。2f32140已推送main，v0.9.17正式Release已发布，附件为已部署K70的172777855字节整合APK；本地交付仅留outputs/ExpressAssistant-v0.9.17.apk。

- 2026-11-16：0.9.16/code30增加独立淘宝XNS身份码，页面按用户最终要求同时展示上菜鸟下淘宝，两张卡片各自刷新，倒计时统一纯MM:SS。本人淘宝重登后新接口SUCCESS、K70实屏双码通过，38件/四平台各1账号保留。79单测、lintRelease零错误（465警告/1提示）、签名整合构建通过；ec33596已提交推送，提交后重建最终整合APK并再次覆盖手机、冷启动状态通过；v0.9.16正式Release已发布并设Latest，附件172777855字节完整上传；上传慢的后续对照已完成：CLI/系统走7897，上传经Vless、API单独DIRECT。GitHub真实32MiB gh上传两轮直连16.61/15.21s、代理56.09/10.58s，代理能高速但波动大，不能断言一直慢或固定配置错误；系统代理/节点未改，临时草稿与数据已清理，正式Release未变。证据在私有输出目录github-upload-route-test.json。最终APK为outputs/ExpressAssistant-v0.9.16.apk。淘宝码依赖淘宝登录态与网络，不能使用菜鸟材料或把CS8改前缀当成XNS。

- 2026-11-16：v0.9.15/code29 单包菜鸟运行环境、SDK 直接登录、设置账号入口与身份码刷新居中已实现，main 的 cf2aaeb 已推送。最终整合 APK 为 outputs/ExpressAssistant-v0.9.15.apk，已签名覆盖 K70；75 单测、lintRelease 0 错误（465 警告/1 提示）。最终资源修正版覆盖后，原生续登、在线身份码及材料刷新再次全部 200，37 件快递与四平台各 1 账号保留。原版 com.cainiao.wireless 已 disable-user、数据保留，不再作为运行时依赖。用户已明确确认手机验证完成并要求直接发布；v0.9.15 已转正式 Release 并设为 Latest，APK 与手机已部署版本保持相同。验收文档提交041d55a已推送main，版本tag指向构建源码cf2aaeb。
- 单包构建必须用 tools/cainiao-host/build.py；Gradle APK 只是界面模块，不能交付代替整合包。使用原有本地 8.11.1023 输入，不下载新菜鸟；JingMatrix/LSPatch v1.2/487 内置模式、个人签名、原包 0x7f/界面 0x7e 资源隔离。Android11+ ResourcesLoader 路径通过 Android16 明暗色检查，API28/29 路径未验收；整合包最低API28、target30、arm64。以下旧版本“必须保留官方菜鸟/独立登录尚未实现”的记录仅描述当时状态，已由本项取代。

- 2026-11-15：v0.9.14/28完成“快递”名称、新启动/单色图标、小组件名称与分组设置页，主页32sp；整合用户明确批准的本机菜鸟Root登录获取。75单测、lintRelease零错误（463警告/1提示）、签名release构建通过。main提交66bf3e2已推送，v0.9.14正式Release已发布；最终APK为outputs/ExpressAssistant-v0.9.14.apk，旧应用交付APK和build内重复APK已清理。主聊天已在2026-11-15覆盖安装K70成功（0.9.14/code28），未清除原数据；真实登录捕获与界面由用户后续自行验收。

- 2026-11-14：v0.9.12/26实现短信与前台剪贴板自动导入、输入框统一格式校验，自动导入设置与权限入口。71单测、lint零错误、release签名及模拟器2集成测试通过；模拟器收件箱补导入/后台新短信/重复去重/超长拒绝通过。此前为独立源码预发布；2026-11-15已获用户确认并与菜鸟登录捕获、品牌UI整合为0.9.14正式版，设备安装验收由主聊天接续。规则与恢复见mobile-navigation.md。

- 2026-11-10：Android v0.9.10/24 修复手动件只入列表却没有轨迹/取件码。立即入库，原生菜鸟按运单校验并读取轨迹、驿站与真实码，查不到时用快递100，列表同步保护并发新增。K70签名覆盖，用户中通待取件样例取得10条轨迹与实际菜鸟取件码，主页/详情/再次同步保留。63单测、lint零错误、release签名通过。手机本轮开始时没有原历史或购物账号；当前菜鸟身份码经同账号手动恢复，原生续登200。官方已是8.11.1105。Root可读加密会话但DDp独立解密472；用户暂停自助登录研究，正式包没有该实验入口。

- 2026-11-09：v0.9.9/23（5165137，main）有效H5会话优先，在线身份码/强制材料刷新不再先加载原生续登；仅明确过期续登并重试一次。K70正式覆盖、online/材料200且续登时间不变、独立session续登200/期限推进，203件及JD/TB/PDD账号各1保留；60单测、lint、release签名通过。内置运行时实机实验未成功：190类补齐OneMain后解决JNI崩溃，仍SecException203(无配置文件)；完整APK/资源对照也未通过。正式包未含实验资源，官方安装仍需保留。实验自写源码/最小结果在outputs/.private/cainiao-runtime/research-embedded-20261109，具体失败链见cainiao-login.md。


- 2026-11-09：v0.9.8/22（113a320，已推送main并发布Release）修复菜鸟列表终态写回原订单；普通同步继续查询已有取件码包裹的原生菜鸟状态（不重复取码），手动单号加入通用查询同步并按真实运单查重。55单测、lint与release构建通过。手机Wi-Fi覆盖安装成功，但随后连接持续超时，当前锁屏，最终冷启动/UI验收待补；本轮读取韵达末4083已是11-09 17:56完成取件、receiptConfirmed=true，PDD末8682仍待取件且保留真实码。官方菜鸟最小运行时只完成静态组件定位，尚未独立加载验收，不可卸载官方App；细节见cainiao-login.md。

- 2026-11-09：Android v0.9.7/21正式签名覆盖K70：独立待取件分区，到站/完成清除历史预计送达，PDD完整轨迹/真实运单→菜鸟原生详情→queryauth→genericverify自动取得真实驿站码。本人留样主页实屏、冷启动与再同步保留通过，203件及三平台账号保留；49单测、lint零错误、签名通过。无需电脑常连；已有官方菜鸟签名运行时仍须保留。临时探针输出已从正式代码移除。手机淘宝会话本轮报失效，依赖它的菜鸟列表失败但旧数据保留；会员取码成功不代表淘宝已恢复。详见package-state.md/cainiao-mobile.md。

- 2026-11-07：Android v0.9.6/20实现菜鸟会员自动续登并签名覆盖K70。原生autologin使用本人refreshToken与消费者路由、完整BaseLoginRequest；加密保存会员对象/设备上下文，先保存轮换凭证再替换Cookie。12小时检查、剩余13小时续登；在线SESSION_EXPIRED自动恢复后只重试一次。真实旧Cookie失效→App内续登→在线200/材料200及冷启动再次续登通过，官方菜鸟进程未运行，手机临时探针已删除；203件/JD/TB/PDD各1账号保留。37单测、lint零错误、release签名通过。当前必须保留官方菜鸟8.11.1023安装作为签名运行时，不启动其进程、无需ADB；独立首次短信登录、无官方运行时、跨30天与驿站扫码未验收。详见cainiao-login.md。
- 2026-11-06：Android v0.9.5/19 已签名覆盖 K70（dcc50feb），只保留莫奈取色；主题入口、温暖纸感、自定义/昼夜独立配置、纸纹与衬线处理均删除。启动时只移除旧主题11键，刷新现有小组件为默认无衬线布局；不再因主题重建主页。实屏设置首项“我的地址”、主题按钮消失；202件、JD/TB/PDD各1账号、有效在线身份会话保留。30单测、lint零错误、release构建与APK签名通过。
- 主项目固定目录 `/Users/meta/Developer/ExpressAssistant`。交付APK、API连接文件、官方APK和研究报告均归项目 `outputs/`（Git忽略）；私有研究会话在 `outputs/.private/cainiao-runtime/`，不再依赖旧聊天x/outputs或临时runtime。18767原型预览已改用新路径；正式PC服务器已迁至ROG的18769；Mac的server/data与运行环境已删除。
- 2026-11-06：Android v0.9.4/18 已签名覆盖 K70（dcc50feb），正式交付仍为主应用；官方菜鸟已获授权临时安装供登录恢复和研究。202件、JD/TB/PDD各1账号保留；本轮实屏3已发货/1未发货。普通快递同步仍沿0.9.2快路径（此前API5.85秒、下拉6.02秒）。Android30项、lint、release构建通过。
- v0.9.4修复商品详情疯狂闪烁：旧轨迹/取件码/ETA分次回写与displayedItem不一致，onResume反复recreate，实机短时间98次重建。DetailState统一回写并读取已保存基线，后续就地重绘保留滚动。TB已发货、PDD已发货/未发货/完成四类实屏均只创建1次、当前实例0销毁，连续屏幕稳定；详见package-state.md。
- 新订单漏显示：TB已付款但尚无SSR运单的订单被丢弃；PDD最新10单来自HTML内rawData.ordersStore.initOdersList，旧版仅抓后续XHR。已保存orderOnly待发货订单，按平台/账号/订单匹配正式运单替换、保留历史及签收证据。不要用PDD orders/currentList摘要替代initOdersList完整订单。
- 普通手机刷新只取最新一页，JD验证页不再静默等60秒，PDD不翻18轮；已完成旧件轨迹改为详情按需读取。维护API POST /api/sync {"includeHistory":true} 保留完整历史补抓。正式版已删除skip_channels Intent逻辑，返回主页按60秒限流检查同步。资源并行聊天提交ad8d988：已完成入口24dp清单图标、48dp触摸区，已安装实看。
- 2026-11-06：PC服务器0.1.5已迁至ROG `http://100.106.169.46:18769`，目录 `C:\Users\Meta\source\repos\ExpressAssistant`。完整7文件迁移校验通过，原令牌/账号ID/Cookie/origins保留；ROG真实同步JD17/TB7/PDD130/菜鸟10，均无错误，历史并集197件（193完成/4运输中）。32项测试、外部REST/MCP、网页详情/账号及任务重启通过。Mac服务器环境与私有数据已删除；Android与菜鸟研究保留。
- ROG迁移已完成。SYSTEM开机任务 `ExpressAssistant Server` 使用现有Python3.12.13、项目.venv与Chrome无头运行；自动同步30分钟，失败重启、忽略重复启动、无运行时限。未重启Windows验收开机触发。生产会话仅留ROG %LOCALAPPDATA%/ExpressAssistantServer/data，访问令牌见该目录config.json/CONNECTION.md；本机outputs连接指南已改ROG地址并清除服务器令牌副本。
- v0.9.1 Store统一签收证据/已有PDD轨迹、详情优先当前Store，解决轨迹已签收而头部营销文本/85%的问题；partial JD/TB/PDD列表不能删旧历史。签收状态不可回退，详情缓存与UI重画规则见package-state.md。
- 身份条形码CODE_128/20字符，600秒周期；本轮实屏跨周期变码且独立生成器匹配。旧Cookie失效已复现；本人官方重登后仅替换cainiao会话，两个刷新API均200，source=online/needsLogin=false，材料延至11月21日；实屏点击变码、CODE128解码与同一账号反解通过。0.9.3在线优先/离线回退与倒计时已验收。手机loopback18768随主进程运行；ROG18769独立。
- .signing/ 私有签名、server/data/账号、outputs私用Markdown令牌禁止进Git/Release。origin=AlexbeatsZ/ExpressAssistant，main；cf2aaeb构建与041d55a验收文档均已推送，v0.9.15正式Release已发布并设Latest；公开API说明仍见server/AI_API.md。服务器0.1.5程序包仍在v0.9.2。源码内不含私有登录态。
- 独立首次登录尚未实现：公开cnlogin固定商家21/cainiao，消费者78/cainiao2c，修改参数无效；不能把商家页面当消费者登录。本人官方短信→Havana→会员login已实测，现v0.9.6已经导入会员对象并用原生refreshToken续登，session约1天/refreshToken约30天且本轮滑动延长；H5续登入口依然拒绝。见cainiao-login.md。

## Active Work


- 手机维护恢复入口：ADB设备按实际在线状态选择（2026-11-22为K70 192.168.31.118:5555）；本轮使用自动分配的桌面端口→手机18768验收，结束已移除转发。令牌只从应用no_backup/phone-control.token由Root读入内存，不输出。升级前备份及脱敏证据保存在outputs/.private/cainiao-runtime/single-package-20261116/。

- 本聊天曾创建干净构建worktree `/Users/meta/.codex/worktrees/express-auto-import-build/ExpressAssistant`，应用将其设为主检出，archive_worktree拒绝归档；attach_worktree也拒绝原主仓库。该worktree已对齐83b5f3d且构建产物已删除。继续修改使用固定主目录 `/Users/meta/Developer/ExpressAssistant`；构建worktree仍待应用切换主检出后归档。

- 会员续登已接入并完成旧Cookie失效恢复/冷启动验收，0.9.15 起使用包内运行时。后续仍需长期跨天/跨30天观察、真实站点扫码；不要将一次滑动续期成功当成永久免登录。

- 小米尚未登录验收。当前无真实待取件码，需后续站点扫码验收。手机京东 Cookie 迁移后跳转登录验证；旧京东记录保留；服务器京东已在ROG复用会话成功抓取17件。手机新会话当前已恢复，后续会员续登尚未实现。

## Build / Run / Test

- App：整合交付先运行 `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`，再运行 `python3 tools/cainiao-host/build.py --java-home /Users/meta/.local/share/mise/installs/java/zulu-21 --android-sdk /Users/meta/Library/Android/sdk`。仅源码检查可用 `./gradlew :app:compileDebugKotlin`（完整：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，与 CI 相同）。
- 服务器：`cd server && uv run express-server [--port 18765 --data ./data]`；测试 `uv run --with pytest pytest -q tests --basetemp /tmp/.agents/expressassistant-pytest`。
- 改动服务器抓取/解析后跑 tests；改网页后用浏览器打开 `/` 实看（令牌在首次启动输出与 `data/config.json`）。

- 当前生产服务在ROG 18769：启动 `Start-ScheduledTask -TaskName 'ExpressAssistant Server'`；停止先执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\Users\Meta\source\repos\ExpressAssistant\server\runtime\Stop-Server.ps1`，再启动即重启。日志在ROG %LOCALAPPDATA%/ExpressAssistantServer/data/server.log与server-error.log，runtime/deployment-acceptance.json为脱敏证据；只有一个写生产data的进程组。Mac不再保留server/data、.venv或常驻服务。

## Durable Lessons

- 京东/拼多多签名（h5st / anti_content）无法自算：服务器同 App 一样让页面自己发请求，在浏览器内读响应（Playwright `page.on("response")`），不要网络层重放。
- 拼多多订单列表靠触摸事件触发增量加载，服务器用 CDP `Input.dispatchTouchEvent` 滑动。
- 京东登录后的订单页预热可能被平台并行跳转打断（net::ERR_ABORTED）；已取得 Cookie 时容忍这一取消，其他网络错误仍报错。
- Hatch 以 server/ 构建 sdist 时，仓库 .gitignore 的 server/data/ 不保证排除根 data/；必须保留 pyproject 的 sdist 白名单并检查归档成员，凭证不进发布包。
- ROG Windows后台运行需Chrome：Edge156在SYSTEM下退出1002，Chrome156同账号启动/抓取通过；18765实际bind报10048，现用18769。数据目录仅在根设Meta/SYSTEM继承权限，再reset子项；递归移除继承会让Store读失败并静默回退空数据，权限变更后必须通过实际Store读取核验。任务停止可能遗留Python子进程，使用runtime/Stop-Server.ps1停完整进程组。
- 当前远程为用户自己的 AlexbeatsZ/ExpressAssistant；AGENTS.md 与 docs/log/ 随本 fork 提交。只有给其他人上游提交 PR 时才排除这些文件。

## Docs Index

- `docs/design/taobao-identity.md`：淘宝XNS身份码官方链路、三分钟前台窗口、单卡片居中切换与上次平台记忆、账号与迟到请求隔离。

- `docs/design/cainiao-embedded.md`：2026-11-16单包宿主、资源编号/合并、SDK直接登录、独立运行验证与构建交付；修改内置宿主前先读。

- `docs/design/cainiao-mobile.md`：手机身份码算法、会话边界、续期、维护 API 与私有签名。
- `server/AI_API.md`：REST/MCP 与手机维护 API 完整说明（公开模板不含令牌）。
- `docs/design/server.md`：PC 服务器版抓取、登录、合并、接口设计（改 server/ 前先读）。
- `server/README.md`：服务器使用说明与接口列表。
- `docs/HANDOFF.md`、`docs/ARCHITECTURE.md`、`docs/API.md`：上游 App 交接、代码地图、四源接口细节。

- `docs/design/mobile-navigation.md`：底部导航、搜索添加、固定莫奈主题与去除内置 AI。
- `docs/THEME.md`：固定莫奈取色、旧主题偏好定向清理与默认小组件布局。
- `docs/design/package-state.md`：终态证据、未知状态与缓存回归。
- `docs/design/cainiao-login.md`：手机号短信登录、SSO 与续登的可行性和未验证边界。

- `docs/design/pixel-settings.md`：用户批准的“快递”品牌、Google Settings分组设置、登录失效持久状态/主页提醒与重新登录交互。
