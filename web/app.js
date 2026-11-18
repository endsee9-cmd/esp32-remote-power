/* 远程电源键 —— 前端逻辑(纯静态,无构建工具)
 *
 * 协议(与固件 power_mqtt.c 严格一致):
 *   发布 <前缀>/cmd   消息 press:<Unix秒,正好10位>(QoS 1, retain=false)
 *   订阅 <前缀>/status 回执 online / done:<ts> / failed:<ts>(QoS 1)
 * 设备约束:时间戳须在 当前时间 -20s ~ +3s 内;同一时间戳不重复执行;
 *           两次动作至少间隔 5 秒;过期/重复命令被设备静默丢弃(不回执)。
 */
'use strict';

const TIMEOUT_MS = 25000;  // 等待回执上限:超时显示“结果未知”,绝不自动重发
const COOLDOWN_MS = 5000;  // 与设备端 5 秒冷却一致,期间锁定按钮防止误触发

const $ = (id) => document.getElementById(id);
const els = {
  connState: $('connState'),
  broker: $('inBroker'), user: $('inUser'), pass: $('inPass'), prefix: $('inPrefix'),
  btnConnect: $('btnConnect'), btnDisconnect: $('btnDisconnect'),
  statusLine: $('statusLine'), btnPress: $('btnPress'), btnNote: $('btnNote'),
  log: $('log'), btnClear: $('btnClear')
};

let client = null;       // mqtt.js 实例
let cfg = null;          // 当前生效配置
let pending = null;      // { ts, startedAt, timer } 等待回执中的命令
let manualClose = false; // 手动断开时不显示“重连中”
let noteTimer = null;

/* ---------------- 配置(保存在 localStorage,README 已注明明文风险) ---------------- */
const CFG_KEY = 'pc-power-config';
function loadConfig() {
  try { return JSON.parse(localStorage.getItem(CFG_KEY)) || {}; } catch { return {}; }
}
function saveConfig() {
  const c = {
    broker: els.broker.value.trim(),
    user: els.user.value,
    pass: els.pass.value,
    prefix: els.prefix.value.trim()
  };
  localStorage.setItem(CFG_KEY, JSON.stringify(c));
  return c;
}
function fillForm(c) {
  els.broker.value = c.broker || '';
  els.user.value = c.user || '';
  els.pass.value = c.pass || '';
  els.prefix.value = c.prefix || '';
}
function validate(c) {
  if (!/^wss?:\/\/.+/.test(c.broker)) return 'Broker 地址必须以 ws:// 或 wss:// 开头';
  if (!c.prefix) return '请填写主题前缀';
  if (c.prefix.endsWith('/')) return '主题前缀末尾不要带 /';
  if (/[\s+#]/.test(c.prefix)) return '主题前缀不能包含空格、+ 或 #';
  return '';
}

/* ---------------- 界面:日志 / 状态 ---------------- */
function stamp() {
  const d = new Date();
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
function log(text, cls = '') {
  const div = document.createElement('div');
  div.className = 'entry ' + cls;
  div.textContent = `[${stamp()}] ${text}`;
  els.log.prepend(div);                       // 最新在最上面
  while (els.log.children.length > 200) els.log.lastChild.remove();
}
function setConn(text, cls) {
  els.connState.textContent = text;
  els.connState.className = 'badge ' + cls;
}
function setStatus(text, cls) {
  els.statusLine.textContent = text;
  els.statusLine.className = 'status ' + cls;
}
/* 设备端 5 秒冷却期间锁定按钮,倒计时后恢复 */
function lockCooldown() {
  let left = COOLDOWN_MS / 1000;
  els.btnPress.disabled = true;
  els.btnNote.hidden = false;
  clearInterval(noteTimer);
  const tick = () => {
    if (left <= 0) {
      clearInterval(noteTimer);
      noteTimer = null;
      els.btnNote.hidden = true;
      els.btnPress.disabled = false;
      return;
    }
    els.btnNote.textContent = `设备冷却中,${left} 秒后可再按`;
    left -= 1;
  };
  noteTimer = setInterval(tick, 1000);
  tick();
}

/* ---------------- MQTT 连接 ---------------- */
function connect() {
  if (client) return;
  const c = saveConfig();
  const err = validate(c);
  if (err) { setStatus(err, 'bad'); return; }
  cfg = c;
  manualClose = false;
  setConn('连接中…', 'warn');
  setStatus('连接 Broker…', 'idle');
  els.btnConnect.disabled = true;

  client = mqtt.connect(c.broker, {
    username: c.user || undefined,
    password: c.pass || undefined,
    clientId: 'web-power-' + Math.random().toString(16).slice(2, 10),
    keepalive: 30,
    reconnectPeriod: 5000,   // 断线后 mqtt.js 自动重连
    connectTimeout: 10000,
    clean: true
  });
  bindClient(client);
}

function bindClient(c) {
  c.on('connect', () => {
    setConn('已连接', 'ok');
    log(`已连接 Broker,订阅 ${cfg.prefix}/status(QoS1)`);
    c.subscribe(cfg.prefix + '/status', { qos: 1 }, (e) => {
      if (e) log('订阅失败:' + (e.message || e), 'bad');
      else log('订阅成功,等待设备 online');
    });
    els.btnDisconnect.disabled = false;
    if (!pending) {
      els.btnPress.disabled = false;
      setStatus('已连接,可以操作', 'idle');
    }
  });
  c.on('reconnect', () => {
    setConn('重连中…', 'warn');
    els.btnPress.disabled = true;
    if (!pending) setStatus('连接断开,自动重连中…', 'warn');
  });
  c.on('close', () => { if (!manualClose) setConn('重连中…', 'warn'); });
  c.on('offline', () => setConn('离线', 'err'));
  c.on('error', (e) => log('连接错误:' + (e && e.message ? e.message : e), 'bad'));
  c.on('message', onMessage);
}

function disconnect() {
  manualClose = true;
  if (pending) {                 // 手动断开时取消等待,结果保持未知
    clearTimeout(pending.timer);
    pending = null;
    setStatus('已断开,当前命令结果未知(请勿短时间内重发)', 'warn');
  }
  if (client) { client.end(false); client = null; }
  clearInterval(noteTimer); noteTimer = null;
  els.btnNote.hidden = true;
  setConn('未连接', 'off');
  if (!els.statusLine.classList.contains('warn')) setStatus('未连接 Broker', 'idle');
  els.btnConnect.disabled = false;
  els.btnDisconnect.disabled = true;
  els.btnPress.disabled = true;
  log('已断开连接');
}

/* ---------------- 回执处理 ---------------- */
function onMessage(topic, payload) {
  if (!cfg || topic !== cfg.prefix + '/status') return;
  const msg = payload.toString();

  if (msg === 'online') {
    log('设备上线 (online)', 'ok');
    return;
  }
  const m = /^(done|failed):(\d{10})$/.exec(msg);
  if (m) {
    log(`设备回执 ${msg}`, m[1] === 'done' ? 'ok' : 'bad');
    if (pending && m[2] === pending.ts) settle(m[1] === 'done' ? 'done' : 'failed');
    // 不匹配当前命令的时间戳仅记日志,不改变当前状态
    return;
  }
  log(`未知回执:${msg}`);
}

/* done / failed / 超时 三种结局统一处理 */
function settle(kind) {
  clearTimeout(pending.timer);
  const waited = Math.round((Date.now() - pending.startedAt) / 1000);
  pending = null;

  if (kind === 'done') {
    setStatus('舵机动作完成', 'ok');
    log(`本地:舵机动作完成(耗时 ${waited} 秒)`, 'ok');
  } else if (kind === 'failed') {
    setStatus('执行失败', 'bad');
    log(`本地:执行失败(耗时 ${waited} 秒)`, 'bad');
  } else {
    setStatus('结果未知(可能已执行,请勿短时间内重发)', 'warn');
    log(`本地:等待 ${waited} 秒无回执,判定结果未知,未自动重发`, 'warn');
  }
  lockCooldown(); // 与设备 5 秒冷却对齐
}

/* ---------------- 主按钮 ---------------- */
function press() {
  if (!client || !client.connected) {
    setStatus('未连接 Broker,请先在设置中连接', 'bad');
    return;
  }
  if (pending) return; // 双保险,按钮平时已禁用
  const ts = String(Math.floor(Date.now() / 1000));
  if (!/^\d{10}$/.test(ts)) { // 固件固定按 10 位解析
    setStatus('本地时间异常,未发送(请校准手机时间)', 'bad');
    return;
  }
  els.btnPress.disabled = true;
  setStatus('已发送,等待设备回执…', 'wait');
  pending = { ts, startedAt: Date.now(), timer: setTimeout(() => settle('timeout'), TIMEOUT_MS) };
  log(`本地:发布 press:${ts}(QoS1, retain=false)`);
  client.publish(cfg.prefix + '/cmd', 'press:' + ts, { qos: 1, retain: false }, (e) => {
    if (e) log('发布失败:' + (e.message || e), 'bad');
  });
}

/* ---------------- 初始化 ---------------- */
fillForm(loadConfig());
els.btnConnect.addEventListener('click', connect);
els.btnDisconnect.addEventListener('click', disconnect);
els.btnPress.addEventListener('click', press);
els.btnClear.addEventListener('click', () => { els.log.textContent = ''; });
log('页面就绪。请展开“连接设置”填写 Broker 地址与主题前缀。');

/* PWA:HTTPS(或本机调试)下注册极简 service worker,支持离线打开页面 */
if ('serviceWorker' in navigator &&
    (location.protocol === 'https:' || ['127.0.0.1', 'localhost'].includes(location.hostname))) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
