/* 本地测试 Broker:aedes v1.x(ESM)+ ws,监听 ws://127.0.0.1:8081(仅测试用)
 * 注:8080 常被其他程序占用,这里用 8081 */
import http from 'node:http';
import { Aedes } from 'aedes';
import { WebSocketServer, createWebSocketStream } from 'ws';

const aedes = await Aedes.createBroker();
const server = http.createServer();
const wss = new WebSocketServer({ server });

wss.on('connection', (websocket, req) => {
  const stream = createWebSocketStream(websocket);
  aedes.handle(stream, req);
});

server.listen(8081, '127.0.0.1', () => console.log('[broker] ready on ws://127.0.0.1:8081'));

aedes.on('client', (c) => console.log('[broker] client connected:', c.id));
aedes.on('subscribe', (subs, c) => console.log('[broker] subscribe by', c && c.id, JSON.stringify(subs.map(s => s.topic))));
aedes.on('publish', (packet, c) => {
  if (packet.cmd === 'publish') {
    console.log(`[broker] ${c ? c.id : 'system'} -> ${packet.topic}: ${packet.payload.toString()} (retain=${packet.retain})`);
  }
});
aedes.on('clientError', (c, err) => console.error('[broker] clientError:', c && c.id, err.message));
