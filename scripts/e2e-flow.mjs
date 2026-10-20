// End-to-end backend flow check against a real server instance:
//   health/config -> bootstrap admin -> generate pairing code -> device
//   redeems code -> device WebSocket connects -> dashboard dispatches a
//   command -> device executes/reports -> dashboard reads final status.
// Verifies the exact API + WebSocket contract the Android app and dashboard use.
import { spawn } from 'child_process';
import WebSocket from 'ws';

const PORT = 3344;
const base = `http://127.0.0.1:${PORT}`;
const server = spawn(process.execPath, ['server/src/index.js'], {
  env: { ...process.env, PORT: String(PORT), NODE_ENV: 'test', JWT_SECRET: 'e2e-flow-secret-1234567890', DATABASE_URL: '', PGHOST: '' },
  stdio: ['ignore', 'pipe', 'pipe']
});
process.on('exit', () => { try { server.kill('SIGKILL'); } catch (_e) {} });

await new Promise((resolve, reject) => {
  let out = '';
  server.stdout.on('data', (d) => { out += d; if (out.includes('running on')) resolve(); });
  server.stderr.on('data', (d) => { out += d; if (out.includes('running on')) resolve(); });
  setTimeout(() => reject(new Error('start timeout ' + out)), 15000);
});

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
};

const api = async (method, route, { token, body } = {}) => {
  const res = await fetch(base + route, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  return { status: res.status, data: await res.json().catch(() => null) };
};

// 1. Health + config endpoints (connectivity check used by the app's Test Connection)
const health = await api('GET', '/api/health');
check('GET /api/health healthy', health.status === 200 && health.data?.status === 'healthy', JSON.stringify(health.data));

const config = await api('GET', '/api/server/config');
check('GET /api/server/config derives serverUrl + wsUrl',
  config.status === 200 && /^http/.test(config.data?.serverUrl || '') && /^ws/.test(config.data?.wsUrl || '') && config.data.wsUrl.endsWith('/ws/device'),
  `serverUrl=${config.data?.serverUrl} wsUrl=${config.data?.wsUrl}`);

// 2. Admin bootstrap + pairing code generation (dashboard side)
const boot = await api('POST', '/api/auth/bootstrap', {
  body: { username: 'e2eadmin', email: 'e2e@example.test', password: 'E2ePass123!' }
});
const adminToken = boot.data?.token;
check('bootstrap admin account', boot.status === 200 && Boolean(adminToken));

const gen = await api('POST', '/api/pairing/generate', {
  token: adminToken,
  body: { environment: 'E2E Test' }
});
const payload = gen.data?.payload;
check('pairing code generated (6 digits + QR payload)',
  gen.status === 200 && /^\d{6}$/.test(gen.data?.code || '') && Boolean(gen.data?.qrDataUrl) && Boolean(payload),
  `code=${gen.data?.code}`);
check('QR payload contract: serverUrl/wsUrl/code/expiresAt keys',
  Boolean(payload?.serverUrl) && Boolean(payload?.wsUrl) && payload?.code === gen.data?.code && Boolean(payload?.expiresAt),
  JSON.stringify(payload));

// 3. Device redeems the pairing code (the exact request the Android agent sends)
const pairRes = await api('POST', '/api/devices/pair', {
  body: {
    pairingCode: gen.data.code,
    deviceName: 'E2E Device',
    manufacturer: 'Test',
    model: 'Emulator',
    osVersion: '15'
  }
});
check('device pairing succeeds and returns device token',
  pairRes.status === 200 && pairRes.data?.success === true && Boolean(pairRes.data?.token) && Boolean(pairRes.data?.deviceId),
  `deviceId=${pairRes.data?.deviceId}`);
const deviceToken = pairRes.data?.token;
const deviceId = pairRes.data?.deviceId;

// 4. Invalid + reused codes return useful errors
const bad = await api('POST', '/api/devices/pair', {
  body: { pairingCode: '000000', deviceName: 'Should Fail', manufacturer: 'x', model: 'x', osVersion: '15' }
});
check('invalid pairing code rejected with error', bad.status >= 400 && Boolean(bad.data?.error || bad.data?.message), JSON.stringify(bad.data));

const reuse = await api('POST', '/api/devices/pair', {
  body: { pairingCode: gen.data.code, deviceName: 'Reuse Try', manufacturer: 'x', model: 'x', osVersion: '15' }
});
check('already-used pairing code rejected', reuse.status >= 400, `status=${reuse.status}`);

// 5. Device WebSocket connects (same URL derivation as the Android agent)
const wsUrl = config.data.wsUrl.replace('ws://0.0.0.0', 'ws://127.0.0.1').replace('ws://localhost', 'ws://127.0.0.1');
const wsFull = `${wsUrl}?deviceId=${encodeURIComponent(deviceId)}&token=${encodeURIComponent(deviceToken)}`;
const ws = new WebSocket(wsFull);
const received = new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('no COMMAND received over WS in 8s')), 8000);
  ws.on('message', (raw) => {
    const msg = JSON.parse(raw.toString());
    if (msg.type === 'COMMAND' && msg.command) {
      clearTimeout(timer);
      resolve(msg.command);
    }
  });
  ws.on('error', reject);
});
await new Promise((resolve, reject) => {
  ws.on('open', resolve);
  ws.on('error', reject);
  setTimeout(() => reject(new Error('WS connect timeout')), 5000);
});
check('device WebSocket connected at /ws/device', ws.readyState === WebSocket.OPEN);

// 6. Dashboard dispatches a supported command
const dispatch = await api('POST', `/api/devices/${deviceId}/commands`, {
  token: adminToken,
  body: { commandType: 'UNLOCK_DEVICE', idempotencyKey: 'e2e-1' }
});
check('command dispatch accepted (UNLOCK_DEVICE)',
  dispatch.status === 200 && Boolean(dispatch.data?.commandId) && dispatch.data?.command?.commandType === 'UNLOCK_DEVICE',
  `commandId=${dispatch.data?.commandId} status=${dispatch.data?.status}`);

// 7. Device receives it, reports honest execution result
const command = await received;
check('device received COMMAND over WebSocket', command.commandType === 'UNLOCK_DEVICE' && Boolean(command.commandId));

const report = await api('POST', `/api/devices/${deviceId}/commands/${command.commandId}/status`, {
  token: deviceToken,
  body: {
    commandId: command.commandId,
    deviceId,
    status: 'COMPLETED',
    result: 'Display is on and the keyguard was dismissed (device has no secure screen lock).'
  }
});
check('device COMMAND_STATUS(COMPLETED) accepted', report.status === 200, JSON.stringify(report.data));

// 8. Dashboard reads the final state
const history = await api('GET', `/api/devices/${deviceId}/commands`, { token: adminToken });
const finalCmd = (history.data || []).find(c => (c.commandId || c.id) === command.commandId);
check('dashboard sees COMPLETED execution result',
  finalCmd?.status === 'COMPLETED' && /keyguard/i.test(finalCmd?.result || ''),
  `status=${finalCmd?.status}`);

const devices = await api('GET', '/api/devices', { token: adminToken });
const listed = (devices.data || []).find(d => d.id === deviceId || d.device_id === deviceId);
check('dashboard device list shows paired device', Boolean(listed));

ws.close();
const failed = results.filter(r => !r.ok).length;
console.log(failed === 0 ? 'E2E_FLOW_PASS' : `E2E_FLOW_FAIL (${failed} failed)`);
process.exit(failed === 0 ? 0 : 1);
