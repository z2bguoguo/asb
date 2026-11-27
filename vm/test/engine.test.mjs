import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { V86 } from 'v86';

const asset = name => fileURLToPath(new URL('../dist/' + name, import.meta.url));

test('boots a guest disk and executes its x86 instructions', { timeout: 12000 }, async () => {
    const disk = new Uint8Array(1024 * 1024);
    disk.set([
        0xba, 0xf8, 0x03, // mov dx, 0x3f8 (serial COM1)
        0xb0, 0x41, 0xee, // mov al, 'A'; out dx, al
        0xb0, 0x53, 0xee, // mov al, 'S'; out dx, al
        0xb0, 0x42, 0xee, // mov al, 'B'; out dx, al
        0xfa, 0xf4, 0xeb, 0xfd // cli; hlt; loop
    ]);
    disk.set([0x55, 0xaa], 510);
    const vm = new V86({
        wasm_path: asset('v86.wasm'), bios: { url: asset('seabios.bin') },
        vga_bios: { url: asset('vgabios.bin') }, hda: { buffer: disk.buffer },
        boot_order: 0x132, memory_size: 32 * 1024 ** 2, autostart: true,
        disable_keyboard: true, disable_mouse: true, disable_speaker: true
    });
    let timer;
    try {
        let output = '';
        await new Promise((resolve, reject) => {
            timer = setTimeout(() => reject(new Error('No guest serial output')), 8000);
            vm.add_listener('serial0-output-byte', byte => {
                output += String.fromCharCode(byte);
                if (output.endsWith('ASB')) resolve();
            });
        });
        assert.match(output, /ASB$/);
        assert.ok(vm.get_instruction_counter() > 0);
        await vm.stop();
        const snapshot = await vm.save_state();
        assert.ok(snapshot.byteLength > 0);
    } finally {
        clearTimeout(timer);
        await vm.destroy();
    }
});
