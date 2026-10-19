// Shared browser bootstrap for the dashboard test scripts.
//
// @sparticuz/chromium only wires up its bundled NSS/NSPR shared libraries
// (al2023.tar.br) automatically on AWS Lambda. On ordinary Linux dev/CI hosts
// Chromium then dies with "libnspr4.so: cannot open shared object file".
// This helper extracts those libraries once and points LD_LIBRARY_PATH at them
// before any browser is launched.
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const pkgBin = join(root, 'node_modules', '@sparticuz', 'chromium', 'bin');

export async function ensureBrowserEnv() {
  const { inflate, setupLambdaEnvironment } = await import('@sparticuz/chromium');
  const libPath = join(tmpdir(), 'al2023', 'lib');
  if (!existsSync(join(libPath, 'libnspr4.so'))) {
    await inflate(join(pkgBin, 'al2023.tar.br'));
  }
  setupLambdaEnvironment(libPath);
}
