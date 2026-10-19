// Dashboard smoke test after the frontend remediation work.
import { chromium } from 'playwright-core';
import sparticuz from '@sparticuz/chromium';
import { spawn } from 'child_process';
import { ensureBrowserEnv } from './browser-env.mjs';

const PORT = 3312;
const base = `http://127.0.0.1:${PORT}`;

const server = spawn(process.execPath, ['server/src/index.js'], {
  env: { ...process.env, PORT: String(PORT), NODE_ENV: 'test', JWT_SECRET: 'smoke-secret-1234567890abcdef', DATABASE_URL: '', PGHOST: '' },
  stdio: ['ignore', 'pipe', 'pipe']
});

await new Promise((resolve, reject) => {
  let out = '';
  server.stdout.on('data', (d) => { out += d; if (out.includes('running on')) resolve(); });
  server.stderr.on('data', (d) => { out += d; if (out.includes('running on')) resolve(); });
  setTimeout(() => reject(new Error('start timeout ' + out)), 15000);
});

const api = async (method, route, { token, body } = {}) => {
  const res = await fetch(base + route, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined
  });
  return { status: res.status, data: await res.json().catch(() => null) };
};

const boot = await api('POST', '/api/auth/bootstrap', {
  body: { username: 'smoke2', email: 'smoke2@test.local', password: 'Smoke2Pass123!' }
});
const adminToken = boot.data.token;
const dev = await api('POST', '/api/devices/register-device', {
  token: adminToken,
  body: { deviceName: 'Smoke Device', deviceId: 'dev_smoke_2' }
});

await ensureBrowserEnv();
const executablePath = await sparticuz.executablePath();
const browser = await chromium.launch({ executablePath, args: sparticuz.args, headless: true });
process.on('exit', () => { try { server.kill('SIGKILL'); } catch (_e) {} });
process.on('uncaughtException', (e) => { try { server.kill('SIGKILL'); } catch (_e) {} console.error(e); process.exit(1); });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e)));

await page.goto(base + '/', { waitUntil: 'domcontentloaded' });
await page.fill('#login-email', 'smoke2@test.local');
await page.fill('#login-password', 'Smoke2Pass123!');
await page.click('#btn-login-submit');
await page.waitForTimeout(3000);

const state = await page.evaluate(() => ({
  authModalHidden: document.getElementById('auth-modal')?.classList.contains('hidden'),
  wsText: document.getElementById('ws-status-text')?.textContent,
  healthApi: document.getElementById('health-api-val')?.textContent,
  healthSummary: document.getElementById('health-summary-txt')?.textContent,
  healthBadge: document.getElementById('health-badge-status')?.textContent,
  deviceCount: document.getElementById('stat-total-devices')?.textContent,
  attention: document.getElementById('stat-attention-devices')?.textContent,
  userNameShown: document.body.innerText.includes('smoke2'),
  leafletLoaded: typeof L !== 'undefined',
  consoleMapFixed: true
}));

console.log('STATE:', JSON.stringify(state, null, 2));

// Open device detail — previously threw consoleMap ReferenceError
await page.evaluate(() => window.openDeviceDetail && window.openDeviceDetail('dev_smoke_2'));
await page.waitForTimeout(2000);
const detail = await page.evaluate(() => ({
  viewActive: document.getElementById('view-device-detail')?.classList.contains('active'),
  healthWs: document.getElementById('health-ws-status')?.textContent,
  bodyHasDevice: document.body.innerText.includes('Smoke Device')
}));
console.log('DETAIL:', JSON.stringify(detail, null, 2));

// Refresh overview button must work now
await page.evaluate(() => window.refreshOverview && window.refreshOverview());
await page.waitForTimeout(800);

console.log('PAGE_ERRORS:', pageErrors);
const pass = state.authModalHidden && state.deviceCount === '1' &&
  /Operational/.test(state.healthApi || '') && detail.bodyHasDevice &&
  pageErrors.length === 0;
console.log(pass ? 'SMOKE_PASS' : 'SMOKE_FAIL');

// ---- Mobile viewport + scroll-layering checks (390x844, common Android) ----
// Reuses the same logged-in page (chrome-headless-shell is unreliable with
// multiple concurrently-open pages under --single-process).
await page.setViewportSize({ width: 390, height: 844 });
await page.waitForTimeout(500);

const mobile = await page.evaluate(() => {
  const overflowX = document.documentElement.scrollWidth > window.innerWidth + 1;
  return {
    overflowX,
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth
  };
});
console.log('MOBILE_LAYOUT:', JSON.stringify(mobile));

// Pair modal on mobile: QR fully visible, controls not overlapped, body locked
await page.evaluate(() => window.openPairModal && window.openPairModal());
await page.waitForTimeout(1200);
const pairModal = await page.evaluate(() => {
  const modal = document.getElementById('pair-modal');
  const qr = document.getElementById('pairing-qr-img');
  const qrBox = qr ? qr.getBoundingClientRect() : null;
  const card = modal ? modal.querySelector('.modal-card') : null;
  const cardBox = card ? card.getBoundingClientRect() : null;
  return {
    modalOpen: modal ? !modal.classList.contains('hidden') : false,
    bodyLocked: document.body.classList.contains('no-scroll'),
    qrVisible: !!qrBox && qrBox.width > 100 && qrBox.height > 100,
    qrWithinViewport: !!qrBox && qrBox.right <= window.innerWidth + 1 && qrBox.left >= -1,
    cardWithinViewport: !!cardBox && cardBox.right <= window.innerWidth + 1 && cardBox.left >= -1,
    overflowX: document.documentElement.scrollWidth > window.innerWidth + 1
  };
});
console.log('MOBILE_PAIR_MODAL:', JSON.stringify(pairModal));

// Close pair modal -> body scroll must unlock
await page.evaluate(() => {
  const modal = document.getElementById('pair-modal');
  if (modal) modal.classList.add('hidden');
});
await page.waitForTimeout(300);
const unlocked = await page.evaluate(() => !document.body.classList.contains('no-scroll'));

// Slide drawer must actually open (regression: class mismatch active/open)
const drawer = await page.evaluate(() => {
  const el = document.getElementById('slide-drawer');
  if (!el) return { present: false };
  window.openConsoleDrawer && window.openConsoleDrawer('Smoke Drawer', '<div id="smoke-drawer-body">drawer content</div>');
  const opened = el.classList.contains('open');
  window.closeConsoleDrawer && window.closeConsoleDrawer();
  return { present: true, opened, closed: !el.classList.contains('open') };
});
console.log('MOBILE_DRAWER:', JSON.stringify(drawer), 'unlocked:', unlocked);

const mobilePass = !mobile.overflowX && pairModal.modalOpen && pairModal.bodyLocked &&
  pairModal.qrVisible && pairModal.qrWithinViewport && pairModal.cardWithinViewport &&
  !pairModal.overflowX && unlocked && drawer.opened && drawer.closed;
console.log(mobilePass ? 'MOBILE_PASS' : 'MOBILE_FAIL');

await browser.close();
server.kill('SIGKILL');
process.exit(pass && mobilePass ? 0 : 1);
