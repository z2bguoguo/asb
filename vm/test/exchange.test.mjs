import { test } from 'node:test';
import assert from 'node:assert/strict';
import { exchangeDisc } from '../src/exchange.mjs';

test('exports an ISO9660 directory entry with the exact input bytes', async () => {
    const payload = new Blob(['应用沙盒\n']);
    const disc = exchangeDisc('INPUT.TXT', payload);
    const bytes = await disc.arrayBuffer();
    const view = new DataView(bytes);
    const text = (at, count) => new TextDecoder().decode(new Uint8Array(bytes, at, count));
    const pvd = 16 * 2048;
    assert.equal(bytes.byteLength % 2048, 0);
    assert.equal(text(pvd + 1, 5), 'CD001');
    assert.equal(view.getUint32(pvd + 80, true) * 2048, bytes.byteLength);
    assert.equal(view.getUint32(pvd + 84, false) * 2048, bytes.byteLength);
    const root = view.getUint32(pvd + 158, true) * 2048;
    const child = root + view.getUint8(root) + view.getUint8(root + view.getUint8(root));
    assert.equal(text(child + 33, view.getUint8(child + 32)), 'INPUT.TXT;1');
    assert.equal(view.getUint32(child + 10, true), payload.size);
    const extent = view.getUint32(child + 2, true) * 2048;
    assert.equal(text(extent, payload.size), '应用沙盒\n');
    assert.throws(() => exchangeDisc('../unsafe', payload), /Invalid exchange file/);
});
