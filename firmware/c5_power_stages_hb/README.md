# ESP32-C5 固件 · 心跳版

手机发一条 MQTT 指令,C5 用 SG90 舵机短按笔记本电源键。本目录是仓库里**唯一的正式固件源码**,
其余工程只作阶段验证。

```
04_remote_power/    正式开机的完整工程(推荐从这里开始)
02_mqtt/            阶段一:只要 MQTT 连通性验证
03_sg90/            阶段二:只要舵机动作验证
components/         三个工程共用的组件
  campus_wifi/        Wi-Fi 接入(开放网络 / 802.1X PEAP)
  power_mqtt/         正式 MQTT 指令通道 + 公网 ping/pong 诊断
  sg90/               GPIO24 PWM 驱动、角度换算、串口微调
```

`02_mqtt` 和 `03_sg90` 是**阶段测试工程**,各自只验证一件事,方便定位问题出在网络还是机械上。
正式部署烧 `04_remote_power`。

## 协议

| 方向 | 主题 | 内容 | QoS / Retain |
|---|---|---|---|
| 手机 → C5 | `<前缀>/cmd` | `press:<10位Unix秒>:<下压角度>:<回位角度>` | 1 / false |
| C5 → 手机 | `<前缀>/status` | `online`(订阅成功后)、`done:<ts>`、`failed:<ts>`、`beat:<ts>`(每 30 秒) | 0 或 1 / false |

- **时间窗口**：只接受当前时间 −20 s ~ +3 s 的指令，窗口外静默丢弃。
- **去重**：已执行过的时间戳写进 NVS，重复指令不再执行；断电重连后旧指令也不会执行。
- **冷却**：两次动作至少间隔 5 秒。
- **兼容**：只带一个角度的指令把这个值用于两个方向；只带时间戳的老格式沿用每段 300 ms。
- **心跳**：`beat:<ts>` 30 秒一次，手机据此判断「设备在线」，而不是「手机连上 Broker」。

`done` 只表示**舵机动作流程走完**，不代表电脑一定开机。

## 动作与接线

- 主板 ESP32-C5-DevKitC（16 MB Flash），舵机信号线接 **GPIO24**，舵机供电与 C5 共地。
- 连续旋转 SG90 用脉宽控制：**1600 µs 下压 → 1500 µs 停转 3 秒 → 1400 µs 反向回位**，最后停转并回执。
- 两段时长按各自角度换算（125 ms / 60°），每项允许 5°–180°。
- 串口 115200：`p` 单独下压，`r` 单独上抬，`a 10` + 回车向下微调、`a -10` + 回车向上微调。
  串口与联网动作共用一把锁，互不打断。

详细接线、供电和固定结构见 [`docs/hardware.md`](../../docs/hardware.md)。

## 配置

所有本机参数都在 `menuconfig` 里填，仓库里只有占位默认值：

```
Power MQTT
  POWER_MQTT_URI         mqtts://your-broker.example.com:8883
  POWER_MQTT_USERNAME    C5 自己的账号
  POWER_MQTT_PASSWORD
  POWER_MQTT_TOPIC_BASE  主题前缀,如 my-room/pc-power(末尾不带 /)
  POWER_MQTT_SNTP_SERVER pool.ntp.org

Campus Wi-Fi
  CAMPUS_WIFI_OPEN       y = 接开放网络
  CAMPUS_WIFI_SSID       你的 Wi-Fi 名称(默认占位 YourCampusWiFi)
  CAMPUS_WIFI_USERNAME   仅 802.1X 需要
  CAMPUS_WIFI_PASSWORD
  CAMPUS_WIFI_RADIUS_DOMAIN  仅 802.1X 需要
```

`menuconfig` 生成的 `sdkconfig` **只留在本机**：`.gitignore` 已排除，`sdkconfig.defaults` 里不要写真实凭据。
本机看到的 SSID 扫描、PEAP 认证和 Portal 认证记录见 [`docs/troubleshooting.md`](../../docs/troubleshooting.md)。

## 构建与烧录

需要 ESP-IDF 6.1.1。

```bash
cd 04_remote_power
idf.py set-target esp32c5
idf.py menuconfig          # 填 Power MQTT 与 Wi-Fi
idf.py -p <你的串口> flash monitor
```

首次完整烧录用 `flash`；之后只改应用代码可以 `idf.py -p <串口> app-flash`，省掉 bootloader 和分区表。
串口名以系统实际枚举为准（Windows 形如 `COM5`，Linux 形如 `/dev/ttyACM0`）。

> 换过 ESP-IDF 的 Python 环境后旧构建缓存会记着原来的解释器路径，`app-flash` 可能被拒绝。
> 遇到就 `idf.py fullclean` 后重新构建。

## 验收清单

- 上电后串口出现 `MQTT subscription acknowledged`，状态主题出现 `online`，之后每 30 秒一条 `beat:<ts>`
- 手机 App 首页显示「设备在线」；拔掉 C5 电源约 90 秒后变「设备离线」
- 电脑关机时，App 与磁贴各发一次：舵机短按、回 `done:<ts>`、结果页显示「电源键已按下」
- 断电重启：不执行旧指令，新指令正常；回执丢失时手机显示「结果未知」且不自动补发
