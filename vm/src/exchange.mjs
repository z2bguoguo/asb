const sector = 2048;
const encoder = new TextEncoder();

function dual16(view, offset, value) {
    view.setUint16(offset, value, true);
    view.setUint16(offset + 2, value, false);
}

function dual32(view, offset, value) {
    view.setUint32(offset, value, true);
    view.setUint32(offset + 4, value, false);
}

function text(bytes, offset, value, width = value.length) {
    bytes.fill(32, offset, offset + width);
    bytes.set(encoder.encode(value), offset);
}

function directory(bytes, offset, id, extent, length, isDirectory) {
    const view = new DataView(bytes.buffer);
    const recordLength = 33 + id.length + (id.length % 2 === 0 ? 1 : 0);
    bytes[offset] = recordLength;
    dual32(view, offset + 2, extent);
    dual32(view, offset + 10, length);
    bytes.set([126, 1, 1, 0, 0, 0, 0], offset + 18);
    bytes[offset + 25] = isDirectory ? 2 : 0;
    dual16(view, offset + 28, 1);
    bytes[offset + 32] = id.length;
    bytes.set(id, offset + 33);
    return recordLength;
}

/** A one-file, non-bootable ISO9660 disc. Blob composition keeps the payload out of JS memory. */
export function exchangeDisc(filename, payload) {
    if (!/^INPUT\.(APK|TXT|BIN)$/.test(filename) || !(payload instanceof Blob)) {
        throw new TypeError('Invalid exchange file');
    }
    if (payload.size > 512 * 1024 * 1024) throw new RangeError('单次传输上限为 512 MiB');
    const root = 20;
    const firstFileSector = root + 1;
    const fileSectors = Math.ceil(payload.size / sector);
    const header = new Uint8Array(firstFileSector * sector);
    const view = new DataView(header.buffer);
    const pvd = 16 * sector;
    header[pvd] = 1;
    text(header, pvd + 1, 'CD001');
    header[pvd + 6] = 1;
    text(header, pvd + 8, 'ASB', 32);
    text(header, pvd + 40, 'ASB_EXCHANGE', 32);
    dual32(view, pvd + 80, firstFileSector + fileSectors);
    dual16(view, pvd + 120, 1);
    dual16(view, pvd + 124, 1);
    dual16(view, pvd + 128, sector);
    dual32(view, pvd + 132, 10);
    view.setUint32(pvd + 140, 18, true);
    view.setUint32(pvd + 148, 19, false);
    directory(header, pvd + 156, [0], root, sector, true);
    for (const position of [190, 318, 446, 574]) text(header, pvd + position, '', 128);
    for (const position of [702, 739, 776]) text(header, pvd + position, '', 37);
    for (const position of [813, 830, 847, 864]) text(header, pvd + position, '0000000000000000');
    header[pvd + 881] = 1;

    const end = 17 * sector;
    header[end] = 255;
    text(header, end + 1, 'CD001');
    header[end + 6] = 1;
    for (const index of [18, 19]) {
        const little = index === 18;
        const offset = index * sector;
        header[offset] = 1;
        view.setUint32(offset + 2, root, little);
        view.setUint16(offset + 6, 1, little);
    }
    let entry = root * sector;
    entry += directory(header, entry, [0], root, sector, true);
    entry += directory(header, entry, [1], root, sector, true);
    directory(header, entry, encoder.encode(filename + ';1'), firstFileSector, payload.size, false);
    const padding = new Uint8Array(fileSectors * sector - payload.size);
    return new File([header, payload, padding], 'asb-exchange.iso', { type: 'application/x-iso9660-image' });
}
