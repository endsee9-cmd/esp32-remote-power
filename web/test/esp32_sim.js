/* 模拟 ESP32-C5 固件(仅测试用):订阅 <前缀>/cmd,校验后 1 秒回执
 * 用法:node esp32_sim.js [主题前缀] [good|bad]
 *   good(默认)→ done:<ts>   bad → failed:<ts>
 * 校验逻辑与 power_mqtt.c 一致:press: + 正好10位数字、时间窗口 -20s~+3s、
 * 时间戳去重、5 秒冷却;窗口外/重复命令静默丢弃(不回执)。
 */
import mqtt from 'mqtt';

const prefix = process.argv[2] || 'my-room/pc-power';
const mode = process.argv[3] === 'bad' ? 'bad' : 'good';
const cmdTopic = `${prefix}/cmd`;
const statusTopic = `${prefix}/status`;
const tag = `[esp32-sim(${mode})]`;

const MAX_AGE = 20;      // 过期阈值(秒)
const FUTURE_TOL = 3;    // 未来容差(秒)
const COOLDOWN = 5000;   // 两次动作最小间隔(毫秒)

let lastAccepted = 0;
let lastAction = 0;
let hasActed = false;

const client = mqtt.connect('ws://127.0.0.1:8081', {
  clientId: `esp32-sim-${mode}`,
  clean: true
});

client.on('connect', () => {
  console.log(`${tag} connected, subscribing ${cmdTopic}`);
  client.subscribe(cmdTopic, { qos: 1 }, (err) => {
    if (err) { console.error(`${tag} subscribe failed`, err.message); return; }
    // 与固件一致:订阅成功后发布 online(QoS1, retain=false)
    client.publish(statusTopic, 'online', { qos: 1, retain: false });
    console.log(`${tag} status -> online`);
  });
});

client.on('message', (topic, payload) => {
  const msg = payload.toString();
  console.log(`${tag} cmd <- ${msg}`);

  // 固件按 10 位定长解析:总长必须正好 6+10=16
  const m = /^press:(\d{10})$/.exec(msg);
  if (!m || msg.length !== 16) { console.log(`${tag}   -> 丢弃:格式非法`); return; }

  const ts = Number(m[1]);
  const now = Math.floor(Date.now() / 1000);
  if (ts <= lastAccepted) { console.log(`${tag}   -> 拒绝:时间戳重复/未增长`); return; }
  if (ts + MAX_AGE < now || ts > now + FUTURE_TOL) {
    console.log(`${tag}   -> 拒绝:超出时间窗口(静默,不回执)`);
    return;
  }
  if (hasActed && Date.now() - lastAction < COOLDOWN) {
    console.log(`${tag}   -> 拒绝:5 秒冷却中(静默,不回执)`);
    return;
  }

  lastAccepted = ts;
  lastAction = Date.now();
  hasActed = true;
  console.log(`${tag}   -> 接受,1 秒后执行动作`);
  setTimeout(() => {
    const reply = `${mode === 'bad' ? 'failed' : 'done'}:${m[1]}`;
    client.publish(statusTopic, reply, { qos: 1, retain: false });
    console.log(`${tag} status -> ${reply}`);
  }, 1000);
});

client.on('error', (e) => console.error(`${tag} error:`, e.message));
