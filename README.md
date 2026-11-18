# esp32-remote-power

在外面用手机发一条指令,让 ESP32-C5 驱动 SG90 舵机按下笔记本的**实体电源键**;电脑起来联网后,
再用远程桌面接进去。

```text
Android 首页 / 控制中心磁贴  (或 web/ 网页版)
        │  带鉴权的 TLS MQTT
        ▼
   MQTT Broker
        │  mqtts://
        ▼
    ESP32-C5  ──GPIO24 PWM──▶  SG90 舵机  ──▶  笔记本电源键
                                                    │
                                          Windows 联网 ──▶ 远程桌面
```

不是 Wake-on-LAN:它真的按物理按键。所以对不支持 WOL 的机器、或者断电后需要按一次电源键才肯起来的
机器同样有效。代价是需要一点机械安装,以及对着一根舵机臂调行程。

> **项目状态:实验项目(v2.6,2026-11)。**
> 已确认:APK 构建与签名校验通过;固件烧录写入并校验通过;重启后自动联网并完成正式 MQTT 订阅;
> 公网 ping/pong 往返;舵机两个方向都能转。
> **尚未确认**:手机点按 → 舵机按到电源键 → 电脑真正启动的完整链路,以及连续多次按压的成功率。
> 具体见「[实测状态](#实测状态)」和 [`docs/test-results.md`](docs/test-results.md)。

## 硬件

| 件 | 说明 |
|---|---|
| ESP32-C5-DevKitC | 16 MB Flash。板载 USB 转串口,烧录与看日志都走它 |
| SG90 舵机 | 本项目用的是**连续旋转**款(给脉宽就转,不给停),不是 0–180° 位置款 |
| 5 V 电源 | 舵机单独供电,和 C5 **共地**。从板子的 5V 引脚取电在动作瞬间容易复位 |
| 支架 / 双面胶 / 扎带 | 把舵机臂固定在电源键上方,见 [`docs/hardware.md`](docs/hardware.md) |

接线:舵机信号线 → **GPIO24**,舵机 VCC/GND → 独立 5 V 与公共地。

## 快速开始

### 1. 准备一个带账号的 MQTT Broker

EMQX Cloud / HiveMQ Cloud / 自建 Mosquitto 都行,要求:

- 开 **TLS** 端口(8883)给 C5,开 **WSS** 端口(8084 之类)给网页版;
- 建两个账号:`phone` 和 `c5`;
- 用 ACL 限定权限,两边只碰自己的主题:

| 账号 | 发布 | 订阅 |
|---|---|---|
| `phone` | `<前缀>/cmd` | `<前缀>/status` |
| `c5` | `<前缀>/status` | `<前缀>/cmd` |

`<前缀>` 像 `my-room/pc-power`,末尾不带 `/`。**手机和板子必须填得一模一样。**

### 2. 构建并烧录固件

需要 ESP-IDF 6.1.1。

```bash
cd firmware/c5_power_stages_hb/04_remote_power
idf.py set-target esp32c5
idf.py menuconfig      # Power MQTT:URI / 账号 / 密码 / 主题前缀;Wi-Fi:SSID
idf.py -p <串口> flash monitor
```

首次用 `flash`(含 bootloader 与分区表),之后只改应用可以 `app-flash`。
串口里看到 `MQTT subscription acknowledged` 就说明板子通了。细节见
[`firmware/c5_power_stages_hb/README.md`](firmware/c5_power_stages_hb/README.md)。

### 3. 装手机端

**APK**(推荐,有控制中心磁贴):

```bash
export ANDROID_TOOLS=/path/to/tools   # JDK17 + android-sdk 的上级目录
bash apk/build_apk.sh
# 产物 apk/pc-power-debug.apk,传到手机安装
```

**网页版**(零安装,纯静态 PWA):把 `web/` 整个目录传到任意 HTTPS 静态托管,
手机浏览器打开,填 WSS 地址和账号即可。见 [`web/README.md`](web/README.md)。

### 4. 配置并试一次

手机上填 Broker 地址、端口、TLS、账号、主题前缀,先点「连接检查」。
板子在线后首页会显示「设备在线」(靠 30 秒心跳判断,不是「手机连上了 Broker」)。
手机关 Wi-Fi 走蜂窝网络,按「远程启动」。

### 5. 调舵机行程

首页「调整舵机行程」分别设**下压角度**和**回位角度**,每项 5°–180°,首次安装默认各 60°。

连续旋转舵机按时长估算行程,**当前位置不被测量**,所以:

- 下压够用但回位过头 → 只减小回位角度;
- 每次只改一项,改完实际按一次看结果;
- 装到电源键上之前先让舵机臂悬空,用串口 `p` / `r` 确认方向,`a 10` / `a -10` 微调起点。

## 仓库结构

```text
esp32-remote-power/
├── README.md
├── CHANGELOG.md
├── docs/
│   ├── hardware.md          接线、供电、固定结构、舵机校准
│   ├── troubleshooting.md   踩过的坑与脱敏证据
│   ├── test-results.md      测试条件、结果、待验证项
│   └── windows-prelogon.md  可选的 Windows 登录前远程桌面配置与还原
├── firmware/
│   └── c5_power_stages_hb/  唯一正式固件 + 两个阶段测试工程
├── apk/                     Android 源码、资源、构建脚本
├── web/                     可选:纯静态 PWA(含本地联调用的 test/)
└── scripts/                 可选:Windows 登录前远控配置脚本
```

## 协议速查

| 项 | 值 |
|---|---|
| 命令 | `<前缀>/cmd` ← `press:<10位Unix秒>:<下压角度>:<回位角度>`,QoS 1,Retain false |
| 回执 | `<前缀>/status` → `online` / `done:<ts>` / `failed:<ts>` / `beat:<ts>`(30 s 心跳) |
| 时间窗口 | 只接受当前时间 −20 s ~ +3 s |
| 去重 | 已执行时间戳写 NVS,重复不执行;断电重连后旧指令作废 |
| 冷却 | 两次动作间隔 ≥ 5 s |
| 超时 | 手机端 25 s 收不到回执 → 显示「结果未知」,**绝不自动重发** |

`done` 的含义是**固件走完了舵机动作流程**。是否真的按到电源键、电脑是否真的开机,是另外两件事,
要分别确认(看电源指示灯、看远程桌面能否连上)。

## 实测状态

| 项 | 状态 |
|---|---|
| APK 2.6 构建 / 签名 / 对齐校验 | ✅ 通过 |
| 固件烧录写入与哈希校验 | ✅ 通过 |
| 断电重启后自动联网 + 正式 MQTT 订阅 | ✅ 通过 |
| 公网 ping/pong 往返(连接诊断) | ✅ 通过 |
| 舵机两个方向转动 | ✅ 通过 |
| 手机指令 → 舵机按到电源键 → 电脑启动 | ⚠️ **待验证** |
| 连续 10 轮按压成功率 | ⚠️ **未测** |
| 登录前(冷启动停在登录界面)远程接入 | ⚠️ 仅一台 Win10 测试机通过,Win11 待验证 |

已知未解决的问题、排查依据和当前落地方案见 [`docs/troubleshooting.md`](docs/troubleshooting.md)。

## 安全须知

- **电脑已开机时再按会触发睡眠/关机。** 固件没有电源状态反馈,它不知道自己按下去的时候机器是开着还是关着。
  手机端有 5 秒冷却和「结果未知不重发」,但拦不住你自己连按。
- 一键开机 = 一个能物理接触你电脑的入口。**公网务必用 TLS + 账号 + 最小权限 ACL**,
  别用匿名 Broker,别把凭据提交进仓库。
- 手机端凭据存在应用私有存储 / localStorage(明文),设备丢了的风险自己评估。
- 舵机堵转会持续发热,安装时留一点余量,别让舵机臂顶着键帽硬顶。

## 文档

- [硬件与固定结构](docs/hardware.md)
- [踩坑与排查](docs/troubleshooting.md)
- [测试记录与待验证项](docs/test-results.md)
- [Windows 登录前远程桌面(可选)](docs/windows-prelogon.md)
- [修改历史](CHANGELOG.md)

## 许可

[MIT](LICENSE)。`web/vendor/mqtt.min.js` 是 [MQTT.js](https://github.com/mqttjs/MQTT.js) 的浏览器构建,
遵循其 MIT 许可,版权归原作者。
