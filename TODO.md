# 待办清单（TODO）

维护人：ashen0116 ｜ 基于上游 yu1989324402/Xu v3.4.0 自研增强
最新发布：v3.4.1（含青春特权、游戏中心、换皮等已完成的改动）

## 已完成（待随下一版发布）

- 静默模式：基础配置新增「禁止打开页面(静默模式)」（默认开），青春特权 / 百次立减 / 游戏中心的浏览类任务统一走 `PageOpenGate`，拦截后只记日志、直接协议上报，不再拉起小程序或网页
- 青春特权代码收敛：完成通道从 6 条 + 双报名收敛为「报名 `taskSignUp` + 上报 `trigger/send`」，单任务每轮 RPC 约 15 → 4；完成判据由 `currentCount` 改为「计数涨 或 `taskStatus` 翻 `COMPLETE`」
- 2026-11-25 17:35 真机实测（静默模式）：`⏸️静默模式已拦截页面打开` 命中 1 次、页面未拉起，`报名→trigger/send` 后状态翻 `COMPLETE`，`🎉任务[攒一笔旅行基金]完成`，单任务单轮 4 次 RPC
- 2026-11-26 上午：青春特权浏览任务在真实任务刷新日复跑确认完成（用户确认收工），静默模式下状态正常收敛
- 2026-11-26：亲密家庭补齐两条任务，改走 `listFamilyTask` + `doFarmTask`（`taskSceneCode=ANTFARM_FAMILY_TASK`）协议上报，两条都不再需要打开页面
  - `一起领外卖红包福利`(FAMILY_SHANGOU，VIEW/JUMP)：实测 `am start` 打开原始 targetUrl 停留 45 秒仍 TODO（`o2i_1st_clk=__CLICK_ID__` 要靠家庭页点击代入）；改用 doFarmTask 上报后回包 `resultCode=100`、`taskStatus=FINISHED`，随后 `RECEIVED`、亲密度 +1
  - `每日捐蛋做好事`(DAILY_DONATE，TRIGGER/DONATION)：根因是「每日捐蛋 | 方式」默认「不捐赠」，代码两处 `donationType != ZERO` 拦截所以永不触发；doFarmTask 上报直接完成，亲密度 +10，不消耗爱心蛋
  - 新增两个开关（默认开）：`亲密家庭 | 领外卖红包福利`、`亲密家庭 | 每日捐蛋做好事`；每条每日只上报一次（`antFarm::familyTakeout::reported` / `antFarm::familyDonate::reported`）

- 2026-11-26：新增「玻弦红包」（支付宝小程序「玻弦打卡」，appId `2021005178678637`）——第三方接口 `tools.boxiantech.com`，不走 MTOP，模块自己发 HTTPS
  - 鉴权：请求头 **小写 `token: <JWT>`** + **POST**（`Authorization` 一律回 `1003`，GET 回 `-1 服务器繁忙`）；`/alipay/auth` 入参名未解（`1001 token授权参数缺失`），改为在响应侧旁路把 token 存盘（`util/BolanToken`，`hook/MiniProgramHook.logMiniResponse` 里调用）
  - 链路：`task/list` → `task/finish {"taskId"}`（回 `data.recordId`）→ `task/prize/receive {"recordId"}`（**按 recordId，不是 taskId**）；`FINISH` 但未领的任务由列表里的 `recordId` 补领
  - 实测（PC 直连同 token）：`BROWSE` / `CPC_READING` / `MERCHANT_PLAN` 三类 `finish` 回 `code=0`；余额 0.06 → 0.14（7 条各 0.01 元）
  - 做不了的类型：`SIMULATION_GUIDE`（`999000000 任务未完成`）、`MEDIA_LINK_POSTBACK` / `XIANYU_JUMP`（`99011 任务未完成`）、`MIDONG_TASK`（`愉悦赚广告id不存在`）——需要真实点击广告或按引导操作，代码里跳过并计数
  - 首次使用需打开一次玻弦红包页面让 Hook 抓到 token；也可关掉「禁止打开页面」并打开「无token时自动打开页面」让功能自己开（默认关）

- 青春特权：浏览任务进度闭环 + 签到状态适配
- 游戏中心：任务采集路径修复 + 假玩上报 + 领奖修复
- 新拟态 UI 换皮与配置页重构、默认值/运行规则统一
- 小程序 / JSBridge / Native 三路抓包探针增强
- 账户切换与各业务模块零星修复、辅助工具类

## 待完成

- [x] 亲密家庭：7 个动作与 7 个设置项都已接上；2026-11-25 实测跑了 6 个
- [x] 亲密家庭补齐两任务（外卖红包福利 / 每日捐蛋做好事）：改为协议上报，见上
- [x] 玻弦红包：2026-11-26 实现（协议 finish + prize/receive；token 靠打开页面旁路捕获），详见上
- [x] 青春特权浏览任务真实刷新验证：2026-11-26 任务刷新日复跑，用户确认完成
- [ ] 亲密家庭「一起运动做公益」(FAMILY_TREAD_MALL，DONATE_STEP)：目前由运动模块捐步带动完成，若某天捐步没跑则仍卡 TODO；是否也加一条协议上报兜底待定

## 已划掉 / 暂不做

- 青春特权「抽奖」：用户决定暂时不做
- 种树攻略｜场景任务 energySceneTask：用户确认已可（手机配置已手动关/清配置生效）
- 游戏中心游戏任务"自动完成"：用户决定不做。瓶颈在游戏方服务器判定，客户端发不出被认可的完成上报，现仅自动领奖；彻底零手动需 hook 游戏原生网络，风险不值当
- 会员/活力兑换跳转（`AntMemberExchange.launchScheme`）：用户决定「保兑换，留跳转」，不纳入静默闸门
- 动动有金：用户决定放弃。调研结论留档——小程序 `2021005173621564`（hybrid 页），后端是第三方 `quchuang.yundps.com`，接口为 `task/getList` / `Task/taskClickLog` / `task/complete` / `movegold/getMoveList`；token 可用但**所有接口强制签名**（直连回 `-2001 签名错误`），签名算法在前端 JS，而该域名只走支付宝 HTTPDNS + 小程序 MINI 通道，取 JS 成本过高
