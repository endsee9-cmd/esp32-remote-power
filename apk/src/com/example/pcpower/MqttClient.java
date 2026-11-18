package com.example.pcpower;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;

/**
 * 极简 MQTT 3.1.1 客户端(纯 Java,无第三方依赖)。
 *
 * 功能范围(刚好覆盖本项目需要):
 *  - CONNECT / CONNACK(校验返回码)
 *  - SUBSCRIBE / SUBACK(接收即回调,授权失败记录日志)
 *  - PUBLISH(QoS1,发送后等待 PUBACK;retain 可指定)
 *  - 接收 PUBLISH(QoS0/1,收到 QoS1 自动回 PUBACK)
 *  - PINGREQ / PINGRESP 保活 + 读超时检测死链
 *  - DISCONNECT 主动断开
 *  - 断线自动重连(指数退避,上限 30 秒)
 *
 * 线程模型:
 *  - 单个网络线程负责 连接→读循环→(断开后)退避重连;
 *  - 所有对输出流的写操作用 sendLock 串行化(发布/订阅/PING 可来自不同线程);
 *  - 所有回调通过主线程 Handler 投递,Activity 无需自行切线程。
 * TLS 使用系统默认 TrustManager(SSLSocketFactory.getDefault()).
 */
public class MqttClient {

    /** 供 Activity 实现的回调(全部已在主线程)。 */
    public interface Listener {
        /** 连接状态变化,取值为 STATE_* 常量。 */
        void onStateChanged(int state);
        /** CONNACK 成功(return code == 0)。 */
        void onConnected();
        void onSubscribed(boolean accepted);
        /** 断开(含自动重连前),reason 为人类可读原因。 */
        void onDisconnected(String reason);
        /** 收到 PUBLISH(QoS0/1)。 */
        void onMessage(String topic, byte[] payload);
        /** 面向用户的日志。 */
        void onLog(String message);
    }

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    // ---- MQTT 控制报文类型(3.1.1) ----
    private static final int MSG_CONNECT = 1;
    private static final int MSG_CONNACK = 2;
    private static final int MSG_PUBLISH = 3;
    private static final int MSG_PUBACK = 4;
    private static final int MSG_SUBSCRIBE = 8;
    private static final int MSG_SUBACK = 9;
    private static final int MSG_PINGREQ = 12;
    private static final int MSG_PINGRESP = 13;

    private static final String TAG = "MqttClient";
    private static final int KEEP_ALIVE_SEC = 30;              // keepalive 30 秒
    private static final int CONNECT_TIMEOUT_MS = 10_000;      // TCP/握手超时
    private static final int READ_TIMEOUT_MS = 45_000;         // 读超时 = 1.5 × keepalive,用于检测死链
    private static final int MAX_PACKET_SIZE = 4096;
    private static final long BACKOFF_MAX_MS = 30_000;         // 重连退避上限

    private final String host;
    private final int port;
    private final boolean tls;
    private final String username;   // 可为 null(匿名)
    private final String password;   // 可为 null(匿名)
    private final String clientId;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sendLock = new Object();
    private final AtomicInteger packetId = new AtomicInteger(0);
    private volatile int subscriptionId;

    private volatile Socket socket;
    private volatile DataInputStream in;
    private volatile BufferedOutputStream out;
    private volatile int state = STATE_DISCONNECTED;
    private volatile boolean userDisconnect = false;   // true = 用户主动断开,不再重连
    private Thread networkThread;
    private ScheduledExecutorService pinger;
    private ScheduledFuture<?> pingTask;

    public MqttClient(String host, int port, boolean tls, String username,
                      String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.username = username == null || username.isEmpty() ? null : username;
        this.password = password == null || password.isEmpty() ? null : password;
        this.listener = listener;
        this.clientId = String.format(Locale.US, "pcpower-%08x", new java.util.Random().nextInt());
    }

    // ---------------------------------------------------------------- 连接管理

    /** 开始连接(已连接/连接中则忽略),并启动保活与自动重连。 */
    public synchronized void connect() {
        if (state != STATE_DISCONNECTED) {
            return;
        }
        userDisconnect = false;
        setState(STATE_CONNECTING);
        networkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                networkLoop();
            }
        }, "mqtt-network");
        pinger = Executors.newSingleThreadScheduledExecutor();
        pingTask = pinger.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sendPingIfAlive();
            }
        }, KEEP_ALIVE_SEC / 3, KEEP_ALIVE_SEC / 2, TimeUnit.SECONDS);
        networkThread.start();
    }

    /** 关闭连接,停止重连与保活。 */
    public synchronized void disconnect() {
        userDisconnect = true;
        stopPing();
        closeQuietly();
        if (networkThread != null) {
            networkThread.interrupt();
        }
        setState(STATE_DISCONNECTED);
    }

    private void networkLoop() {
        long backoff = 1_000;
        while (!userDisconnect) {
            String failReason = null;
            try {
                connectOnce();                 // 抛异常 = 连接/握手/CONNACK 失败
                backoff = 1_000;               // 连上过一次,重置退避
                readLoop();                    // 正常返回意味着对端关闭
                failReason = "服务器关闭了连接";
            } catch (Exception e) {
                if (userDisconnect) {
                    break;
                }
                failReason = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.w(TAG, "MQTT 连接/读异常: " + failReason);
            }
            closeQuietly();
            if (userDisconnect) {
                break;
            }
            setState(STATE_DISCONNECTED);
            listenerPost().onDisconnected("连接断开(" + failReason + "),正在自动重连…");
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                return;
            }
            if (userDisconnect) {
                break;
            }
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);   // 指数退避,上限 30 秒
            setState(STATE_CONNECTING);
        }
        stopPing();
        closeQuietly();
    }

    /** 建立一次连接:TCP(/TLS)→ CONNECT → 校验 CONNACK。 */
    private void connectOnce() throws IOException {
        subscriptionId = 0;
        Socket s = connectSocket(InetAddress.getAllByName(host));
        s.setSoTimeout(READ_TIMEOUT_MS);
        if (tls) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket encrypted = (SSLSocket) factory.createSocket(s, host, port, true);
            SSLParameters parameters = encrypted.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            encrypted.setSSLParameters(parameters);
            encrypted.startHandshake();
            s = encrypted;
        }
        out = new BufferedOutputStream(s.getOutputStream(), 4096);
        in = new DataInputStream(s.getInputStream());

        sendConnect();
        int rc = readConnAck();
        if (rc != 0) {
            throw new IOException("服务器拒绝连接: " + connackMessage(rc));
        }
        setState(STATE_CONNECTED);
        listenerPost().onConnected();
    }

    private Socket connectSocket(InetAddress[] addresses) throws IOException {
        IOException lastFailure = null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS);
        for (int i = 0; i < addresses.length; i++) {
            InetAddress address = addresses[i];
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                break;
            }
            int timeout = (int) Math.max(1, remaining / (addresses.length - i));
            Socket candidate = new Socket();
            synchronized (this) {
                if (userDisconnect) {
                    candidate.close();
                    throw new IOException("连接已取消");
                }
                socket = candidate;
            }
            String family = address instanceof Inet6Address ? "IPv6" : "IPv4";
            try {
                candidate.connect(new InetSocketAddress(address, port), timeout);
                listenerPost().onLog("TCP 已连接（" + family + "）");
                return candidate;
            } catch (IOException e) {
                closeQuietly();
                lastFailure = e;
                if (!userDisconnect) {
                    listenerPost().onLog(family + " 连接失败：" + e.getMessage());
                }
            }
        }
        throw new IOException("无法连接 " + host + ":" + port,
                lastFailure);
    }

    // ---------------------------------------------------------------- 读循环

    /** 阻塞读取并分发所有入站报文;任何异常都视为连接失效,由外层负责重连。 */
    private void readLoop() throws IOException {
        while (true) {
            int first;
            try {
                first = in.readUnsignedByte();
            } catch (SocketTimeoutException te) {
                throw new IOException("读超时(" + (READ_TIMEOUT_MS / 1000) + "s 无任何数据),判定连接失效");
            }
            int remaining = readRemainingLength();
            byte[] body = new byte[remaining];
            in.readFully(body);
            int type = first >> 4;
            switch (type) {
                case MSG_PUBLISH:
                    handlePublish(first, body);
                    break;
                case MSG_PUBACK:
                    listenerPost().onLog("收到 PUBACK");
                    break;
                case MSG_SUBACK:
                    handleSuback(body);
                    break;
                case MSG_PINGRESP:
                    // 保活应答,无需处理
                    break;
                default:
                    // CONNACK 等其它报文在别处处理,忽略
                    break;
            }
        }
    }

    /** 解析 MQTT 变长剩余长度(最多 4 字节)。 */
    private int readRemainingLength() throws IOException {
        int value = 0;
        int multiplier = 1;
        for (int i = 0; i < 4; i++) {
            int digit = in.readUnsignedByte();
            value += (digit & 0x7F) * multiplier;
            if ((digit & 0x80) == 0) {
                if (value > MAX_PACKET_SIZE) {
                    throw new IOException("MQTT 报文过大");
                }
                return value;
            }
            multiplier *= 128;
        }
        throw new IOException("剩余长度编码非法(超过 4 字节)");
    }

    private void handlePublish(int first, byte[] body) throws IOException {
        boolean retain = (first & 0x01) != 0;
        int qos = (first >> 1) & 0x03;
        if (body.length < 2 || qos > 1) {
            throw new IOException("PUBLISH 报文格式错误");
        }
        int i = 0;
        int topicLen = ((body[i++] & 0xFF) << 8) | (body[i++] & 0xFF);
        if (topicLen == 0 || topicLen > body.length - i) {
            throw new IOException("PUBLISH 主题长度错误");
        }
        String topic = new String(body, i, topicLen, StandardCharsets.UTF_8);
        i += topicLen;
        if (qos > 0) {
            if (body.length < i + 2) {
                throw new IOException("PUBLISH 报文长度不足");
            }
            int pid = ((body[i++] & 0xFF) << 8) | (body[i++] & 0xFF);
            if (qos == 1) {
                // 必须应答,否则服务器会按 QoS1 语义反复重发
                sendPacket(MSG_PUBACK << 4, new byte[] {(byte) (pid >> 8), (byte) (pid & 0xFF)});
            }
        }
        byte[] payload = new byte[body.length - i];
        System.arraycopy(body, i, payload, 0, payload.length);
        if (retain) {
            Log.i(TAG, "收到 retain 消息 " + topic);
        }
        listenerPost().onMessage(topic, payload);
    }

    private void handleSuback(byte[] body) {
        if (body.length == 3
                && (((body[0] & 0xff) << 8) | (body[1] & 0xff)) == subscriptionId) {
            subscriptionId = 0;
            listenerPost().onSubscribed(body[2] == 0 || body[2] == 1);
        }
    }

    private int readConnAck() throws IOException {
        // CONNACK 可能不是第一个到达的报文(理论上),循环直到读到 CONNACK
        while (true) {
            int first = in.readUnsignedByte();
            int type = first >> 4;
            int remaining = readRemainingLength();
            byte[] body = new byte[remaining];
            in.readFully(body);
            if (type == MSG_CONNACK) {
                if (body.length < 2) {
                    throw new IOException("CONNACK 报文长度不足");
                }
                return body[1] & 0xFF;
            }
            Log.i(TAG, "CONNACK 前收到 type=" + type + ",跳过");
        }
    }

    // ---------------------------------------------------------------- 发送

    /** 订阅主题(QoS1)。 */
    public void subscribe(String topic, int qos) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int pid = nextPacketId();
        b.write(pid >> 8);
        b.write(pid & 0xFF);
        writeMqttString(b, topic);
        b.write(qos);
        // SUBSCRIBE 固定头必须带 0x02 标志位
        subscriptionId = pid;
        sendPacket((MSG_SUBSCRIBE << 4) | 0x02, b.toByteArray());
        listenerPost().onLog("订阅请求已发送: " + topic);
    }

    /**
     * 发布消息。
     *
     * @return 报文 id(QoS1 时),发送成功即返回;PUBACK 由读循环异步处理
     */
    public int publish(String topic, byte[] payload, int qos, boolean retain) throws IOException {
        if (state != STATE_CONNECTED) {
            throw new IOException("MQTT 未连接");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeMqttString(b, topic);
        int pid = 0;
        if (qos > 0) {
            pid = nextPacketId();
            b.write(pid >> 8);
            b.write(pid & 0xFF);
        }
        b.write(payload);
        int flags = (qos << 1) | (retain ? 1 : 0);
        sendPacket((MSG_PUBLISH << 4) | flags, b.toByteArray());
        return pid;
    }

    private void sendConnect() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeMqttString(b, "MQTT");
        b.write(0x04);   // protocol level 4 = MQTT 3.1.1
        int flags = 0x02;   // clean session
        if (username != null) {
            flags |= 0x80;
        }
        if (username != null && password != null) {
            flags |= 0x40;
        }
        b.write(flags);
        b.write((KEEP_ALIVE_SEC >> 8) & 0xFF);
        b.write(KEEP_ALIVE_SEC & 0xFF);
        writeMqttString(b, clientId);
        if (username != null) {
            writeMqttString(b, username);
        }
        if (username != null && password != null) {
            writeMqttString(b, password);
        }
        sendPacket(MSG_CONNECT << 4, b.toByteArray());
    }

    private void sendPingIfAlive() {
        try {
            if (state == STATE_CONNECTED) {
                sendPacket(MSG_PINGREQ << 4, new byte[0]);
            }
        } catch (Exception e) {
            // PING 失败 → 关闭 socket,让读循环抛异常进入重连
            closeQuietly();
        }
    }

    /**
     * 统一的发送入口:固定头 1 字节 + 变长剩余长度 + 报文体。
     * 所有写操作串行化,避免多线程交错破坏报文边界。
     */
    private void sendPacket(int fixedFirst, byte[] body) throws IOException {
        BufferedOutputStream o = out;
        if (o == null) {
            throw new IOException("连接未建立");
        }
        synchronized (sendLock) {
            o.write(fixedFirst);
            writeVarLen(o, body.length);
            o.write(body);
            o.flush();
        }
    }

    /** 写 MQTT 变长剩余长度编码。 */
    private void writeVarLen(BufferedOutputStream o, int len) throws IOException {
        do {
            int digit = len % 128;
            len /= 128;
            if (len > 0) {
                digit |= 0x80;
            }
            o.write(digit);
        } while (len > 0);
    }

    /** 写 MQTT 长度前缀字符串。 */
    private void writeMqttString(ByteArrayOutputStream b, String s) throws IOException {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        if (data.length > 0xFFFF) {
            throw new IOException("字符串过长");
        }
        b.write((data.length >> 8) & 0xFF);
        b.write(data.length & 0xFF);
        b.write(data);
    }

    private int nextPacketId() {
        return packetId.updateAndGet(previous -> previous >= 0xFFFF ? 1 : previous + 1);
    }

    // ---------------------------------------------------------------- 状态与工具

    private void setState(int newState) {
        state = newState;
        listenerPost().onStateChanged(newState);
    }

    private void stopPing() {
        ScheduledExecutorService p = pinger;
        if (p != null) {
            p.shutdownNow();
        }
        pinger = null;
        pingTask = null;
    }

    private Listener listenerPost() {
        return new Listener() {
            @Override
            public void onStateChanged(final int s) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!userDisconnect) listener.onStateChanged(s);
                    }
                });
            }

            @Override
            public void onConnected() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!userDisconnect) listener.onConnected();
                    }
                });
            }

            @Override
            public void onSubscribed(boolean accepted) {
                main.post(() -> {
                    if (!userDisconnect) listener.onSubscribed(accepted);
                });
            }

            @Override
            public void onDisconnected(final String reason) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!userDisconnect) listener.onDisconnected(reason);
                    }
                });
            }

            @Override
            public void onMessage(final String topic, final byte[] payload) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!userDisconnect) listener.onMessage(topic, payload);
                    }
                });
            }

            @Override
            public void onLog(final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!userDisconnect) listener.onLog(message);
                    }
                });
            }
        };
    }

    private void closeQuietly() {
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                // 已断开
            }
        }
    }

    private String connackMessage(int rc) {
        switch (rc) {
            case 0: return "接受";
            case 1: return "协议版本不支持";
            case 2: return "客户端 ID 被拒绝";
            case 3: return "服务器不可用";
            case 4: return "用户名或密码错误";
            case 5: return "未授权";
            default: return "未知返回码 " + rc;
        }
    }
}
