# Android APK · 远程开机 2.6

日常两个入口：打开应用点「远程启动」，或下拉控制中心点「远程开机」磁贴。两个入口共用同一份配置和
同一条进行中的操作，都向 ESP32-C5 发送一次 `press:<10位Unix秒>:<下压角度>:<回位角度>`，
C5 驱动 SG90 按下并松开笔记本电源键。

纯 Java + 自实现 MQTT 3.1.1 客户端，**无第三方依赖、无 AndroidX、无 Gradle**，`build_apk.sh` 纯命令行构建。

- 包名 `com.example.pcpower`，minSdk 24，targetSdk 34
- 权限：仅 `INTERNET`；磁贴服务声明 `BIND_QUICK_SETTINGS_TILE`
- 产物：`apk/pc-power-debug.apk`

## 一、界面结构

| 页面 | 内容 |
|---|---|
| 首页 | 标题「远程开机」+ 右上设置入口；目标电脑名称、C5 连接状态、设备在线状态、上次操作结果；主按钮「远程启动」；等待期禁用；警示「电脑已开机时再按可能触发睡眠/关机」；「调整舵机行程」分别设置下压与回位角度 |
| 设置 | 目标名称、MQTT 地址/端口/TLS/账号/主题前缀；「连接检查」（只连不发）、「添加到控制中心」、「连接诊断」入口 |
| 连接诊断 | 公网联调（连 `test.mosquitto.org:8886`，订阅 `/pong`，每 3 秒发 `/ping` 带 16 位 nonce，收到原样回传即「联调通过」；75 秒超时；绝不发 press）、当前连接状态、设备可用性、诊断日志（首行显示 App 版本） |
| 结果页 | 磁贴点击后自动弹出并自动发送：已发送 →「电源键已按下」/「执行失败」/「结果未知，先查看电脑状态」；设备离线时提示「指令可能无法送达」 |

状态措辞只用文字——「设备在线」「等待回执」「电源键已按下」「结果未知」。
只有收到 C5 的 `done` 回执才显示「电源键已按下」；应用不显示也不暗示「Windows 已启动」。

## 二、安装与首次配置

1. 把 `pc-power-debug.apk` 传到手机安装（允许未知来源）。
2. 打开应用 → 设置：填目标电脑名称、MQTT 地址、端口（8883）、开 TLS、账号密码、主题前缀
   （须与 C5 `menuconfig` 里的完全一致）→ 保存。
3. 点「连接检查」确认能连上 Broker；建议先用「连接诊断 → 公网联调」验证手机侧链路。
4. 点设置里的「添加到控制中心」，在系统弹窗确认；也可在控制中心编辑页手动添加。
5. 首页「调整舵机行程」分别设下压与回位角度，每项 5°–180°，首次安装默认各 60°。
6. 联调要点：先让 C5 上电连上 Broker（串口见 `MQTT subscription acknowledged`），
   手机关 Wi-Fi 用移动数据，先按 App 后按磁贴各测一次；ESP32 断电重启后确认不执行旧指令。

## 三、磁贴行为

- 点一次 = 收起控制中心并打开结果页自动发送，无需再点确认（Android 15 用
  `startActivityAndCollapse(PendingIntent)`；低版本走 Intent 重载）。
- 应用进程未运行时也能走完整流程（结果页拉起 PowerManager 惰性建连）。
- 锁屏时点击，系统先要求解锁再打开结果页；配置缺失时直接进设置。
- 连点、切换入口或旋转屏幕只发一次（共用 PowerManager 的进行中操作；结果页按点击 nonce 去重）。
- 磁贴为一次性动作按钮，不是开关；未配置时显示为不可用。

## 四、协议与安全（与固件对齐）

- 先订阅 `<前缀>/status`，再以 QoS 1、Retain=false 发布 `press:<10位Unix秒>:<下压角度>:<回位角度>` 到 `<前缀>/cmd`；
- 按 `done:<ts>` / `failed:<ts>` 与本次时间戳匹配回执；25 秒收不到判「结果未知」，**绝不自动补发**；
- 沿用板端 20 秒有效期、5 秒冷却、NVS 去重；真正发送时才生成新时间戳（C5 启动期间等待就绪）；
- 设备在线 = 90 秒内收到 `online` 或心跳 `beat:<ts>`（需烧录本仓库的心跳版固件）；
- 手机账号只发布 cmd、订阅 status；C5 账号只订阅 cmd、发布 status；公网务必 TLS。
- 正式配置会拒绝 `test.mosquitto.org`：公共服务器无鉴权，只允许在「连接诊断」里联调。

## 五、构建

工具链不在仓库里（体积大、且是通用软件），用环境变量指向本机安装位置：

```bash
export ANDROID_TOOLS=/path/to/tools   # 下面应能看到 jdk17/ 和 android-sdk/
export JDK17_HOME=/path/to/jdk-17     # 可省略,默认取 $ANDROID_TOOLS/jdk17/* 唯一一项
export ANDROID_SDK=/path/to/android-sdk
bash apk/build_apk.sh
```

需要 JDK 17、build-tools 34.0.0、platforms/android-34。流程：
aapt2 compile + link（`res/`）→ javac → d8 → 打包 → zipalign → apksigner。

**签名**：仓库不含密钥库。首次构建时脚本会用固定口令生成一个调试用 `apk/debug.keystore`（已 `.gitignore`）。
自己正式发布请换成自己的密钥库，并保管好——换签名后旧版本无法覆盖安装。

**图标**：`res/mipmap-*` 里的 20 张图由 `tools-src/GenIcons.java` 从一张方图生成
（legacy 方形/圆形 + adaptive 前景 + 渐变背景）。换图标时：

```bash
javac -d /tmp/genicons apk/tools-src/GenIcons.java
java -cp /tmp/genicons GenIcons <源图.png> apk
```

## 六、已知限制

- 未做真机/模拟器实测，仅编译 + 签名 + 对齐校验；装机后如异常先看「连接诊断」日志（首行是版本号，
  可确认装的是哪个构建）；
- 「电源键已按下」只代表舵机动作完成，电脑是否实际启动以远程桌面能否连上为准；
- 结果未知/过期操作不自动补发；电脑已开机时再按会触发睡眠或关机（固件无电源状态反馈）；
- 明文 TCP 仅限本地调试，公网请保持 TLS 开启；
- 磁贴语言/图标跟随系统，未做多语言。
