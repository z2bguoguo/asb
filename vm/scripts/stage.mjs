import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const output = new URL('dist/', root);
const firmwareCache = new URL('.firmware/', root);
const firmwareCommit = '9457e7a2f6890f3133bb6ac0f749e588f63c4c36';
const firmware = {
    'seabios.bin': '73e3f359102e3a9982c35fce98eb7cd08f18303ac7f1ba6ebfbe6cdc1c244d98',
    'vgabios.bin': 'a4bc0d80cc3ca028c73dafa8fee396b8d054ce87ebd8abfbd31b06b437607880',
};

async function verified(path, sha256) {
    try {
        return createHash('sha256').update(await readFile(path)).digest('hex') === sha256;
    } catch (error) {
        if (error.code !== 'ENOENT') throw error;
        return false;
    }
}

await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await mkdir(firmwareCache, { recursive: true });
await cp(new URL('src/', root), output, { recursive: true });
for (const name of ['libv86.mjs', 'v86.wasm', 'v86-fallback.wasm']) {
    await cp(new URL('node_modules/v86/build/' + name, root), new URL(name, output));
}
await cp(new URL('node_modules/v86/LICENSE', root), new URL('v86-LICENSE.txt', output));
for (const [name, sha256] of Object.entries(firmware)) {
    const cached = new URL(name, firmwareCache);
    if (!(await verified(cached, sha256))) {
        execFileSync('curl', ['-fLsS', '--max-time', '90', '--retry', '2',
            'https://raw.githubusercontent.com/copy/v86/' + firmwareCommit + '/bios/' + name,
            '-o', fileURLToPath(cached)], { stdio: 'inherit' });
    }
    if (!(await verified(cached, sha256))) throw new Error('Firmware SHA-256 mismatch: ' + name);
    await cp(cached, new URL(name, output));
}
