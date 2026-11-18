package com.example.pcpower;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全应用共用逻辑单例(首页 / 设置 / 诊断 / 磁贴 / 结果页共用)。
 *
 * 职责:
 *  - 持有唯一的正式 MQTT 会话(按配置连接,断线自动重连);
 *  - 持有唯一的「进行中开机操作」,首页与磁贴共用,连点/切换入口只发一次;
 *  - 协议对齐 ESP32 端 power_mqtt.c:先订阅回执,再以 QoS1、Retain=false
 *    发布 press:<10位Unix秒>:<下压角度>:<回位角度> 到 <前缀>/cmd;按时间戳匹配 done:/failed: 回执;
 *    25 秒收不到回执判「结果未知」,绝不自动补发;回执后 5 秒冷却;
 *  - 设备可用性:凭 /status 上的 online / beat:<10位秒> 心跳刷新,90 秒窗口判定;
 *    「手机连上 MQTT」与「C5 在线」严格分开;
 *  - 诊断日志环形缓冲 + 公网联调(只查链路,绝不发 press)。
 *
 * 线程模型:MqttClient 回调已在主线程;网络写操作走单线程 executor;
 * 状态与日志用 lock 保护;监听回调一律主线程。
 */
public final class PowerManager {

    public enum OpState { IDLE, CONNECTING, WAITING_RECEIPT, PRESSED, FAILED, UNKNOWN }

    /** UNKNOWN=从未收到过心跳/online;ONLINE=90 秒内见过;STALE=超过 90 秒。 */
    public enum DeviceAvail { UNKNOWN, ONLINE, STALE }

    public interface Listener {
        void onConnectionChanged(boolean connected);
        void onDeviceAvailChanged(DeviceAvail avail);
        /** detail:PRESSED→"done:<ts>";FAILED→"failed:<ts>";UNKNOWN→"回执超时"。 */
        void onOpStateChanged(OpState state, long ts, String detail);
        /** 诊断日志行,已带 [HH:mm:ss] 前缀。 */
        void onLog(String line);
    }

    public interface LinkTestCallback { void onDone(String summary); }

    public static final int MIN_PRESS_DEGREES = 5;
    public static final int MAX_PRESS_DEGREES = 180;
    private static final int DEFAULT_PRESS_DEGREES = 60;

    public static final class Config {
        public String targetName = "";
        public String broker = "";
        public String port = "8883";
        public String user = "";
        public String pass = "";
        public String prefix = "";
        public boolean tls = true;

        /** broker/port/prefix 齐备才算可用。 */
        public boolean valid() {
            return !broker.trim().isEmpty() && !port.trim().isEmpty() && !prefix.trim().isEmpty();
        }
    }

    // ---- 常量(与固件 power_mqtt.c 对齐) ----
    private static final String PREFS = "pcpower_prefs";
    private static final long RECEIPT_TIMEOUT_MS = 25_000;   // 回执超时→结果未知
    private static final long COOLDOWN_MS = 5_000;           // 回执后冷却,对齐固件
    private static final long AVAIL_FRESH_MS = 90_000;       // 心跳新鲜窗口(心跳周期 30s)
    private static final long AVAIL_TICK_MS = 30_000;        // 可用性复检周期
    private static final long LINK_TEST_TIMEOUT_MS = 75_000; // 公网联调上限
    private static final long LINK_TEST_RETRY_MS = 3_000;    // ping 周期重发(v1.3 同款)
    // v1.3 配套板端固件(campus_wifi 探针)使用的固定联调主题与端口
    private static final String LINK_TEST_HOST = "test.mosquitto.org";
    private static final int LINK_TEST_PORT = 8886;
    private static final String LINK_TEST_PREFIX = "c5-link/000000000000";
    private static final int LOG_MAX = 300;

    // ---- SharedPreferences 键:沿用 v1.3,新增的单独列出 ----
    private static final String KEY_BROKER = "broker";
    private static final String KEY_PORT = "port";
    private static final String KEY_TLS = "tls";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";
    private static final String KEY_PREFIX = "prefix";
    private static final String KEY_TARGET_NAME = "target_name";
    private static final String KEY_LAST_RESULT = "last_result";
    private static final String KEY_LAST_TS = "last_ts";
    private static final String KEY_PRESS_DEGREES = "press_degrees";
    private static final String KEY_RETURN_DEGREES = "return_degrees";

    private static volatile PowerManager instance;

    public static PowerManager get(Context ctx) {
        if (instance == null) {
            synchronized (PowerManager.class) {
                if (instance == null) {
                    instance = new PowerManager(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();

    private Config config = new Config();
    private MqttClient client;                    // 正式会话,随配置更换重建
    private volatile boolean connected;
    private volatile boolean subscribed;          // <前缀>/status 已 SUBACK
    private Listener listener;

    // 进行中的操作
    private OpState opState = OpState.IDLE;
    private long opTs;                            // 本次指令时间戳(10 位 Unix 秒)
    private String opDetail;
    private long cooldownUntil;                   // 回执后的 5 秒冷却截止
    private boolean timeoutScheduled;
    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            onReceiptTimeout();
        }
    };

    // 设备可用性
    private long lastSeen;                        // 最近一次 online/beat 时刻(毫秒)
    private DeviceAvail avail = DeviceAvail.UNKNOWN;
    private boolean availTickScheduled;

    // 诊断
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private boolean linkTestRunning;
    private MqttClient linkTestClientRef;
    private String linkNonce;          // 联调 nonce(16 位 hex,pong 原样回传即通过)
    private boolean linkSent;          // 至少成功发出过一次 ping

    // 上次操作结果(持久化,进程重启后首页仍可显示)
    private String lastResult = "";
    private long lastResultTs;

    private PowerManager(Context appContext) {
        app = appContext;
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config c = new Config();
        c.targetName = prefs.getString(KEY_TARGET_NAME, "");
        c.broker = prefs.getString(KEY_BROKER, "");
        c.port = prefs.getString(KEY_PORT, "8883");
        c.tls = prefs.getBoolean(KEY_TLS, true);
        c.user = prefs.getString(KEY_USER, "");
        c.pass = prefs.getString(KEY_PASS, "");
        c.prefix = prefs.getString(KEY_PREFIX, "");
        config = c;
        lastResult = prefs.getString(KEY_LAST_RESULT, "");
        lastResultTs = prefs.getLong(KEY_LAST_TS, 0L);
        String version;
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            version = "?";
        }
        log("逻辑核心 v" + version + " 初始化,已读取配置(" + (c.valid() ? "前缀 " + c.prefix : "未配置") + ")");
    }

    // ---------------------------------------------------------------- 对外接口

    /** UI/结果页注册监听(单监听;onPause 时置 null)。 */
    public void setListener(Listener l) {
        synchronized (lock) {
            listener = l;
        }
        if (l != null) {
            // 注册时把当前状态完整推一遍,页面重建后能恢复
            l.onConnectionChanged(connected);
            l.onDeviceAvailChanged(getDeviceAvail());
            l.onOpStateChanged(getOpState(), opTs, opDetail);
        }
    }

    /** 幂等连接:已连接/连接中忽略;配置缺失记日志不动作。 */
    public void ensureConnected() {
        Config c;
        synchronized (lock) {
            c = config;
            if (connected || (client != null && !c.valid())) {
                if (!c.valid()) log("未配置 Broker,无法连接");
                return;
            }
            if (client != null) {
                return; // 已在连接/重连循环中
            }
        }
        if (!c.valid()) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(c.port.trim());
        } catch (NumberFormatException e) {
            log("端口格式错误,无法连接");
            return;
        }
        log("开始连接 " + c.broker + ":" + port + (c.tls ? " (TLS)" : " (明文)"));
        MqttClient.Listener ml = new MqttClient.Listener() {
            @Override
            public void onStateChanged(int state) {
                if (state == MqttClient.STATE_CONNECTING) log("MQTT 连接中…");
            }

            @Override
            public void onConnected() {
                connected = true;
                log("CONNACK 接受,TLS/认证通过");
                notifyConnection();
                // 先订阅回执主题(手机账号只订阅 status),再允许发送
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            client.subscribe(statusTopic(), 1);
                        } catch (Exception e) {
                            log("订阅失败:" + e.getMessage());
                        }
                    }
                });
            }

            @Override
            public void onSubscribed(boolean accepted) {
                synchronized (lock) {
                    subscribed = accepted;
                }
                log(accepted ? "已订阅回执主题 " + statusTopic() + "(SUBACK)"
                             : "订阅被 Broker 拒绝,请检查账号 ACL");
                notifyConnection();
            }

            @Override
            public void onDisconnected(String reason) {
                connected = false;
                synchronized (lock) {
                    subscribed = false;
                }
                log(reason);
                notifyConnection();
            }

            @Override
            public void onMessage(String topic, byte[] payload) {
                handleStatusMessage(topic, payload);
            }

            @Override
            public void onLog(String message) {
                log("[链路] " + message);
            }
        };
        MqttClient newClient = new MqttClient(c.broker.trim(), port, c.tls,
                c.user, c.pass, ml);
        synchronized (lock) {
            client = newClient;
        }
        newClient.connect();
    }

    /** 断开正式会话(不清理配置)。 */
    public void shutdown() {
        MqttClient c;
        synchronized (lock) {
            c = client;
            client = null;
            connected = false;
            subscribed = false;
        }
        if (c != null) {
            c.disconnect();
        }
        notifyConnection();
    }

    /**
     * 唯一发送入口(首页与磁贴共用)。
     * 有进行中操作或冷却期内返回 false(不重复发);未连接/未订阅也返回 false。
     * 真正发送时才生成新的 10 位 Unix 秒时间戳。
     */
    public boolean sendPress() {
        final long ts;
        final int pressDegrees;
        final int returnDegrees;
        final MqttClient connection;
        final String topic;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (opState == OpState.WAITING_RECEIPT) {
                log("已有进行中的操作,忽略本次请求");
                return false;
            }
            if (now < cooldownUntil) {
                log("冷却期内(" + (cooldownUntil - now) + "ms),忽略本次请求");
                return false;
            }
            if (!connected || !subscribed) {
                log("未就绪(" + (!connected ? "未连接" : "未订阅回执") + "),不发送");
                return false;
            }
            opTs = now / 1000L;
            opState = OpState.WAITING_RECEIPT;
            opDetail = null;
            ts = opTs;
            pressDegrees = getPressDegrees();
            returnDegrees = getReturnDegrees();
            connection = client;
            topic = cmdTopic();
        }
        final String payload = String.format(Locale.US, "press:%010d:%d:%d", ts, pressDegrees, returnDegrees);
        log("发布 " + topic + " ← " + payload + " (QoS1, retain=false)");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (connection == null) throw new IOException("会话不存在");
                    connection.publish(topic, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, false);
                    scheduleReceiptTimeout();
                } catch (Exception e) {
                    log("发布失败:" + e.getMessage());
                    synchronized (lock) {
                        if (opState == OpState.WAITING_RECEIPT && opTs == ts) {
                            setOpStateLocked(OpState.FAILED, ts, "发布失败:" + e.getMessage());
                        }
                    }
                }
            }
        });
        return true;
    }

    public boolean hasInFlight() {
        synchronized (lock) {
            return opState == OpState.WAITING_RECEIPT;
        }
    }

    public int getPressDegrees() {
        return clampAngle(prefs.getInt(KEY_PRESS_DEGREES, DEFAULT_PRESS_DEGREES));
    }

    public int getReturnDegrees() {
        return clampAngle(prefs.getInt(KEY_RETURN_DEGREES, getPressDegrees()));
    }

    private static int clampAngle(int degrees) {
        return Math.max(MIN_PRESS_DEGREES, Math.min(degrees, MAX_PRESS_DEGREES));
    }

    public void savePressAngles(int pressDegrees, int returnDegrees) {
        if (pressDegrees != clampAngle(pressDegrees) || returnDegrees != clampAngle(returnDegrees)) {
            throw new IllegalArgumentException("Press angle out of range");
        }
        synchronized (lock) {
            prefs.edit()
                    .putInt(KEY_PRESS_DEGREES, pressDegrees)
                    .putInt(KEY_RETURN_DEGREES, returnDegrees)
                    .apply();
        }
    }

    public OpState getOpState() {
        synchronized (lock) {
            return opState;
        }
    }

    public DeviceAvail getDeviceAvail() {
        synchronized (lock) {
            if (lastSeen == 0) return DeviceAvail.UNKNOWN;
            return System.currentTimeMillis() - lastSeen <= AVAIL_FRESH_MS
                    ? DeviceAvail.ONLINE : DeviceAvail.STALE;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isReadyToSend() {
        return connected && subscribed;
    }

    public long getLastOpTs() {
        synchronized (lock) {
            return opTs;
        }
    }

    /** 持久化的上次操作结果,如「电源键已按下 · 14:23」。 */
    public String getLastResultText() {
        synchronized (lock) {
            if (lastResult == null || lastResult.isEmpty()) return "";
            if (lastResultTs <= 0) return lastResult;
            return lastResult + " · " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(lastResultTs));
        }
    }

    public Config getConfig() {
        synchronized (lock) {
            return config;
        }
    }

    /** 保存配置并持久化;正式会话已在连接中时断开重连以应用新配置。 */
    public void saveConfig(Config c) {
        synchronized (lock) {
            // 归一化前缀:去尾部 '/',拒绝通配符
            String prefix = c.prefix == null ? "" : c.prefix.trim();
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            c.prefix = prefix;
            config = c;
            prefs.edit()
                    .putString(KEY_TARGET_NAME, nz(c.targetName))
                    .putString(KEY_BROKER, nz(c.broker))
                    .putString(KEY_PORT, nz(c.port))
                    .putBoolean(KEY_TLS, c.tls)
                    .putString(KEY_USER, nz(c.user))
                    .putString(KEY_PASS, nz(c.pass))
                    .putString(KEY_PREFIX, prefix)
                    .apply();
            log("配置已保存(前缀 " + prefix + ",TLS " + (c.tls ? "开" : "关") + ")");
        }
        MqttClient old;
        synchronized (lock) {
            old = client;
            client = null;
            connected = false;
            subscribed = false;
        }
        if (old != null) {
            old.disconnect();
        }
        notifyConnection();
        if (c.valid()) {
            ensureConnected();
        }
    }

    /**
     * 公网联调(与 v1.3 及配套板端固件完全一致):
     * TLS 连 test.mosquitto.org:8886 → 订阅 <固定前缀>/pong(QoS1)→ 每 3 秒发布
     * <固定前缀>/ping(QoS0,16 位随机 nonce)→ 收到原样回传 nonce 的 pong 即「联调通过」;
     * 75 秒超时。需要板端烧录配套 ping/pong 固件;绝不发送 press 开机命令。
     */
    public void runPublicLinkTest(final LinkTestCallback cb) {
        synchronized (lock) {
            if (linkTestRunning) {
                log("公网联调已在进行中");
                if (cb != null) cb.onDone(null);
                return;
            }
            linkTestRunning = true;
            linkSent = false;
            linkNonce = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String nonce = linkNonce;
        log("公网联调开始(" + LINK_TEST_HOST + ":" + LINK_TEST_PORT + ",TLS,主题 "
                + LINK_TEST_PREFIX + "/ping)");
        final long start = System.currentTimeMillis();
        final MqttClient test = new MqttClient(LINK_TEST_HOST, LINK_TEST_PORT, true, null, null,
                new MqttClient.Listener() {
                    @Override
                    public void onStateChanged(int state) {
                        if (state == MqttClient.STATE_CONNECTING) log("[联调] TCP/TLS 连接中…");
                    }

                    @Override
                    public void onConnected() {
                        log("[联调] CONNACK 成功");
                        io.execute(new Runnable() {
                            @Override
                            public void run() {
                                MqttClient c;
                                synchronized (lock) {
                                    c = linkTestClientRef;
                                }
                                if (c == null) return;
                                try {
                                    c.subscribe(LINK_TEST_PREFIX + "/pong", 1);
                                } catch (Exception e) {
                                    log("[联调] 订阅失败:" + e.getMessage());
                                }
                            }
                        });
                    }

                    @Override
                    public void onSubscribed(boolean accepted) {
                        if (!accepted) {
                            finishLinkTest(cb, "联调失败:公网 MQTT 回执主题订阅被拒绝");
                            return;
                        }
                        log("[联调] 已订阅 " + LINK_TEST_PREFIX + "/pong,开始周期发送 ping");
                        main.removeCallbacks(linkTestRetry);
                        main.post(linkTestRetry);
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        log("[联调] " + reason);
                    }

                    @Override
                    public void onMessage(String topic, byte[] payload) {
                        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
                        if (nonce.equals(text) && topic.equals(LINK_TEST_PREFIX + "/pong")) {
                            long cost = (System.currentTimeMillis() - start) / 1000;
                            finishLinkTest(cb, "联调通过:手机收到 C5 的回信(耗时 " + cost + " 秒)");
                        }
                    }

                    @Override
                    public void onLog(String message) {
                        log("[联调] " + message);
                    }
                });
        synchronized (lock) {
            linkTestClientRef = test;
        }
        test.connect();
        main.postDelayed(linkTestTimeout, LINK_TEST_TIMEOUT_MS);
    }

    /** 周期重发 ping(每 3 秒),板端什么时候在线都能收到一条新的。 */
    private final Runnable linkTestRetry = new Runnable() {
        @Override
        public void run() {
            MqttClient t;
            boolean running;
            synchronized (lock) {
                t = linkTestClientRef;
                running = linkTestRunning;
            }
            if (!running || t == null || linkNonce == null) return;
            linkSent = true;
            final byte[] payload = linkNonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        t.publish(LINK_TEST_PREFIX + "/ping", payload, 0, false);
                        log("[联调] ping 已发送(QoS0)");
                    } catch (Exception e) {
                        log("[联调] ping 发送失败:" + e.getMessage());
                    }
                }
            });
            main.postDelayed(this, LINK_TEST_RETRY_MS);
        }
    };

    /** 75 秒兜底超时(v1.3 同款文案)。 */
    private final Runnable linkTestTimeout = new Runnable() {
        @Override
        public void run() {
            finishLinkTest(null, linkSent
                    ? "75 秒内未收到 C5 回信,请检查板子供电和联网状态"
                    : "75 秒内未能向 MQTT 发送测试消息,请查看连接日志");
        }
    };

    private void finishLinkTest(LinkTestCallback cb, String summary) {
        boolean first;
        MqttClient t;
        synchronized (lock) {
            first = linkTestRunning;
            t = linkTestClientRef;
            linkTestRunning = false;
            linkTestClientRef = null;
            linkNonce = null;
            linkSent = false;
        }
        main.removeCallbacks(linkTestRetry);
        main.removeCallbacks(linkTestTimeout);
        if (t != null) {
            t.disconnect();   // 结束后关闭测试会话,不留在后台重连
        }
        if (summary != null) {
            log(summary);
        }
        if (first && cb != null) {
            final String s = summary;
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.onDone(s);
                }
            });
        }
    }

    public List<String> diagnosticsSnapshot() {
        synchronized (lock) {
            return new ArrayList<>(logs);
        }
    }

    public void clearDiagnostics() {
        synchronized (lock) {
            logs.clear();
        }
        log("诊断日志已清空");
    }

    // ---------------------------------------------------------------- 内部实现

    private String statusTopic() {
        synchronized (lock) {
            return config.prefix.trim() + "/status";
        }
    }

    private String cmdTopic() {
        synchronized (lock) {
            return config.prefix.trim() + "/cmd";
        }
    }

    /** /status 消息分发:online / beat:<ts> 心跳 / done:<ts> / failed:<ts> 回执。 */
    private void handleStatusMessage(String topic, byte[] payload) {
        String s;
        try {
            s = new String(payload, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return;
        }
        if (!topic.equals(statusTopic())) {
            log("收到非回执主题消息 " + topic + ",忽略");
            return;
        }
        if (s.equals("online")) {
            touchLastSeen("设备上线(online)");
            return;
        }
        if (s.startsWith("beat:")) {
            String digits = s.substring(5);
            long ts = parseTs(digits);
            long now = System.currentTimeMillis() / 1000L;
            if (ts >= now - AVAIL_FRESH_MS / 1000 && ts <= now + 3) {
                touchLastSeen(null);   // 心跳每 30s 一条,不逐条写日志刷屏
            } else {
                log("心跳时间戳无效(" + digits + "),丢弃");
            }
            return;
        }
        if (s.startsWith("done:") || s.startsWith("failed:")) {
            long ts = parseTs(s.substring(s.indexOf(':') + 1));
            synchronized (lock) {
                if (opState == OpState.WAITING_RECEIPT && ts == opTs) {
                    main.removeCallbacks(timeoutTask);
                    timeoutScheduled = false;
                    boolean ok = s.startsWith("done:");
                    setOpStateLocked(ok ? OpState.PRESSED : OpState.FAILED, ts, s);
                    cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS;
                } else {
                    log("收到与本次操作不匹配的回执(" + s + "),忽略");
                }
            }
            return;
        }
        log("status 未知消息:" + s);
    }

    private void onReceiptTimeout() {
        synchronized (lock) {
            timeoutScheduled = false;
            if (opState != OpState.WAITING_RECEIPT) return;
            setOpStateLocked(OpState.UNKNOWN, opTs, "回执超时");
        }
        log("25 秒未收到回执,标记「结果未知」;不自动补发");
    }

    private void scheduleReceiptTimeout() {
        synchronized (lock) {
            if (timeoutScheduled) {
                main.removeCallbacks(timeoutTask);
            }
            timeoutScheduled = true;
        }
        main.postDelayed(timeoutTask, RECEIPT_TIMEOUT_MS);
    }

    /** 必须持有 lock 调用。 */
    private void setOpStateLocked(OpState state, long ts, String detail) {
        opState = state;
        opDetail = detail;
        if (state == OpState.PRESSED || state == OpState.FAILED || state == OpState.UNKNOWN) {
            lastResultTs = System.currentTimeMillis();
            lastResult = state == OpState.PRESSED ? "电源键已按下"
                    : state == OpState.FAILED ? "执行失败"
                    : "结果未知,先查看电脑状态";
            prefs.edit()
                    .putString(KEY_LAST_RESULT, lastResult)
                    .putLong(KEY_LAST_TS, lastResultTs)
                    .apply();
        }
        final Listener l = listener;
        if (l != null) {
            l.onOpStateChanged(state, ts, detail);
        }
    }

    private void touchLastSeen(String logLine) {
        synchronized (lock) {
            lastSeen = System.currentTimeMillis();
        }
        if (logLine != null) log(logLine);
        recomputeAvail(true);
    }

    /** 周期复检:窗口过期后把 ONLINE 翻成 STALE 并通知。 */
    private void recomputeAvail(boolean forceNotifyIfChanged) {
        DeviceAvail now = getDeviceAvail();
        boolean changed;
        synchronized (lock) {
            changed = now != avail;
            avail = now;
        }
        if ((changed || forceNotifyIfChanged) && changed) {
            final Listener l;
            synchronized (lock) {
                l = listener;
            }
            if (l != null) {
                l.onDeviceAvailChanged(now);
            }
            if (changed) {
                log("设备可用性:" + (now == DeviceAvail.ONLINE ? "在线" : now == DeviceAvail.STALE ? "离线(心跳超时)" : "未知"));
            }
        }
        scheduleAvailTick();
    }

    private void scheduleAvailTick() {
        synchronized (lock) {
            if (availTickScheduled) return;
            availTickScheduled = true;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    availTickScheduled = false;
                }
                recomputeAvail(false);
            }
        }, AVAIL_TICK_MS);
    }

    private void notifyConnection() {
        final Listener l;
        synchronized (lock) {
            l = listener;
        }
        if (l != null) {
            l.onConnectionChanged(connected);
        }
    }

    private void log(String line) {
        if (line == null) return;
        String stamped = new SimpleDateFormat("[HH:mm:ss]", Locale.getDefault())
                .format(new Date()) + " " + line;
        final Listener l;
        synchronized (lock) {
            logs.addFirst(stamped);
            while (logs.size() > LOG_MAX) {
                logs.removeLast();
            }
            l = listener;
        }
        if (l != null) {
            final String s = stamped;
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onLog(s);
                }
            });
        }
    }

    /** 解析 10 位数字时间戳;格式不对返回 0。 */
    private static long parseTs(String digits) {
        if (digits == null || digits.length() != 10) return 0;
        long v = 0;
        for (int i = 0; i < 10; i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return 0;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
