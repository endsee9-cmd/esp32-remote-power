# 远程电源键 —— 手机网页(直连 MQTT 模式)

纯静态页面,无构建工具。手机浏览器打开后,通过 **MQTT over WSS 直连 Broker**,向 ESP32-C5 发送
「短按电源键」命令,由设备端舵机完成物理按压。

> **状态:可选组件,验证范围比 APK 小。**
> 已完成验证的只有「本地浏览器 + 本地 Broker(aedes)+ ESP32 模拟器」这条链路:
> 发送、回执、超时、冷却、配置持久化都过了。**没有在真实手机上对真实 C5 跑过。**
>
> 协议版本:本页只发**旧格式** `press:<Unix秒>`,不带角度。固件收到后按「每段 300 ms」执行,
> 用的是固定行程,**不受手机端 2.6 的下压/回位角度设置影响**。需要调行程请用 APK。

## 协议约定(与固件 `power_mqtt.c` 一致)

| 项目 | 值 |
| --- | --- |
| 命令主题 | `<前缀>/cmd`(发布) |
| 回执主题 | `<前缀>/status`(订阅) |
| 命令内容 | `press:<Unix秒时间戳>` —— 时间戳**正好 10 位数字** |
| QoS | 1 |
| Retain | 必须 false |
| 回执 | 订阅成功 `online`;动作完成 `done:<同一时间戳>`;失败 `failed:<同一时间戳>` |
| 时间窗口 | 设备只接受 当前时间 −20 秒 ~ +3 秒 内的时间戳,窗口外静默丢弃 |
| 去重 | 同一时间戳不重复执行;断线重连后旧命令也不会执行 |
| 冷却 | 两次动作至少间隔 5 秒 |

页面行为:点击大按钮 → 发布 `press:<ts>` → 状态「已发送,等待设备回执…」→
收到 `done` 显示「舵机动作完成」/ `failed` 显示「执行失败」/ 25 秒超时显示
「结果未知(可能已执行,请勿短时间内重发)」。**超时绝不自动重发**;
回执后按钮再锁定 5 秒,与设备冷却时间对齐。

## 文件清单

```
web/
├── index.html            页面结构(全中文,移动端优先)
├── app.js                连接/发布/回执/日志逻辑
├── style.css             深色友好的响应式样式
├── manifest.webmanifest  PWA 清单(添加到主屏幕)
├── sw.js                 极简 service worker(离线可打开页面)
├── icon.svg / icon-maskable.svg  图标
└── vendor/mqtt.min.js    本地内嵌的 MQTT.js v4.3.7 浏览器构建(不依赖 CDN)
```

## 部署

把 `web/` 下所有文件原样上传到**任意 HTTPS 静态托管**即可,例如:

- GitHub Pages / Cloudflare Pages / Vercel / Netlify
- 自建 nginx(需要配置 TLS 证书,如 Let's Encrypt)

必须是 HTTPS(或局域网 `http://127.0.0.1` 调试):浏览器禁止在 HTTPS 页面里建立
不加密的 `ws://` 连接(混合内容限制),托管 Broker 需使用 `wss://` 地址。

## 为什么用 MQTT over WSS

- ESP32 固件走 `mqtts://`,手机页运行在浏览器里,只能用 WebSocket 方式接入 MQTT,
  即 `wss://`(TLS 加密,和 HTTPS 页面兼容)。
- WSS 复用同一套 Broker、同一套主题与凭据体系,手机↔Broker↔设备 三方通过公共
  Broker 解耦,手机不用和设备在同一局域网,也不需要公网穿透到家里。
- QoS 1 + 空窗口校验 + NVS 去重,保证「按一次、只按一次」。

## 与托管 Broker 对接(以 EMQX Cloud / HiveMQ Cloud 为例)

1. 注册并创建 Serverless / Dedicated 实例。
2. 在控制台创建 MQTT 凭据(用户名/密码)。
3. 确认实例开启了 **WSS 端口**:
   - EMQX Cloud Serverless:连接地址形如 `wss://xxxx.ala.cn-hangzhou.emqxsl.cn:8084/mqtt`;
   - HiveMQ Cloud:形如 `wss://xxxxxxxx.s1.eu.hivemq.cloud:8884/mqtt`(TLS WebSocket)。
4. 在页面「连接设置」里填:Broker 地址(上面的 wss://…)、用户名、密码、
   主题前缀(与设备 menuconfig 中 `POWER_MQTT_TOPIC_BASE` 一致,如 `my-room/pc-power`)。
5. 点「连接」,等右上角变「已连接」、日志出现设备 `online`,即可使用。
6. 建议:在 Broker 侧为该账号配置 ACL,只允许它收发 `<前缀>/cmd` 与 `<前缀>/status` 两个主题。

## 添加到主屏幕(PWA)

- iOS Safari:打开页面 → 分享按钮 → 「添加到主屏幕」。
- Android Chrome:打开页面 → 菜单 → 「添加到主屏幕 / 安装应用」。
- 安装后以独立窗口运行;`sw.js` 会缓存页面壳,无网络时页面本身也能打开
  (MQTT 连接自然仍需要网络)。

## 与「HTTPS 后端持有凭据」方案的关系

本页是**直连模式**:手机浏览器直接连托管 Broker,Broker 的用户名/密码保存在
手机 localStorage(明文,仅本机可读),不经过任何自建服务。它的优点是零后端、
零运维;代价是凭据存放在终端,且 Broker 账号凭据分发到每台设备。

后续可选的**后端模式**:部署一个 HTTPS 小后端(持有 Broker 凭据,提供
`POST /press` 等接口并校验登录/限流),手机只与后端通信,后端再转发 MQTT 命令。
这样手机端无需知道 Broker 凭据,可以接入账号体系、操作审计和多用户权限,
代价是多维护一个服务。本页不实现该模式,仅在事件日志与协议层保持一致,
便于日后切换。

## 本地自测(可选)

`test/` 目录内置本地验证环境(仅测试用,不属于交付页面):

```bash
cd test
npm install        # 安装 aedes / ws / mqtt
node broker.js     # 本地 Broker,监听 ws://127.0.0.1:8081(8080 常被其他程序占用)
node esp32_sim.js my-room/pc-power good   # 模拟设备(正常回 done)
# node esp32_sim.js my-room/pc-power bad  # 模拟设备(回 failed)
npx http-server ../ -p 9090               # 起静态页面
# 浏览器打开 http://127.0.0.1:9090,Broker 填 ws://127.0.0.1:8081
```
