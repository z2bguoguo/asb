import { V86 } from '/libv86.mjs';
import { exchangeDisc } from '/exchange.mjs';

const get = id => document.getElementById(id);
const engineVersion = 'v86-0.5.476';
const checkpointKey = 'asb-checkpoint-v1';
const diskSize = 2 * 1024 ** 3;
const image = file => ({ buffer: file, async: true });
let session;
let operation = false;
let hostFocus = document.hasFocus();
let queue = Promise.resolve();

function report(message) {
    get('status').textContent = message;
}

function setupControls() {
    get('configuration').hidden = !!session;
    get('console').hidden = !session;
    for (const button of document.querySelectorAll('button')) button.disabled = operation;
    get('restore').disabled ||= !localStorage.getItem(checkpointKey);
    get('save').disabled ||= !session?.diskName || session?.hasDisc;
}

async function safely(action) {
    if (operation) return;
    operation = true;
    setupControls();
    try {
        await action();
    } catch (error) {
        report(error.message || String(error));
    } finally {
        operation = false;
        setupControls();
    }
}

function syncFocus() {
    queue = queue.catch(() => {}).then(async () => {
        const current = session;
        if (!current?.ready) return;
        const hasInput = hostFocus && !current.paused && !current.checkpointing;
        current.vm.keyboard_set_enabled(hasInput);
        current.vm.mouse_set_enabled(hasInput);
        if (!hasInput && document.pointerLockElement) document.exitPointerLock();
        const running = !current.paused && !current.checkpointing
                && (hostFocus || current.focus === 'input');
        if (running && !current.vm.is_running()) await current.vm.run();
        if (!running && current.vm.is_running()) await current.vm.stop();
        get('pause').textContent = current.paused ? '继续' : '暂停';
        if (!operation) report(running ? '来宾运行中' : '来宾已暂停');
    });
    return queue;
}

window.setHostFocus = function (focused) {
    hostFocus = focused;
    syncFocus().catch(error => report(String(error)));
};

async function storage() {
    if (!navigator.storage?.getDirectory) throw new Error('此 WebView 不支持私有磁盘，请更新 Android System WebView');
    return navigator.storage.getDirectory();
}

async function createDisk() {
    const root = await storage();
    const name = 'disk-' + crypto.randomUUID() + '.raw';
    const handle = await root.getFileHandle(name, { create: true });
    let writer;
    try {
        writer = await handle.createWritable();
        await writer.truncate(diskSize);
        await writer.close();
    } catch (error) {
        if (writer) await writer.abort().catch(() => {});
        await root.removeEntry(name).catch(() => {});
        throw error;
    }
    await loadDisks();
    get('persistent').value = name;
    report('已创建 2 GiB 私有磁盘');
}

async function loadDisks() {
    const select = get('persistent');
    const previous = select.value;
    select.replaceChildren(new Option('无', ''));
    if (!navigator.storage?.getDirectory) return;
    const root = await storage();
    for await (const [name, handle] of root.entries()) {
        if (/^disk-[a-f0-9-]+\.raw$/.test(name) && handle.kind === 'file'
                && (await handle.getFile()).size === diskSize) {
            select.add(new Option('私有磁盘 ' + name.slice(5, 13), name));
        }
    }
    select.value = [...select.options].some(option => option.value === previous) ? previous : '';
}

async function boot(restore) {
    let root;
    const saved = restore ? JSON.parse(localStorage.getItem(checkpointKey)) : null;
    if (saved && saved.engine !== engineVersion) throw new Error('检查点来自不同版本的虚拟 CPU');
    const diskName = saved?.diskName ?? get('persistent').value;
    const iso = restore ? null : get('iso').files[0];
    const temporary = restore ? null : get('disk').files[0];
    if (diskName) root = await storage();
    if (diskName && temporary) throw new Error('请选择一种主磁盘');
    const disk = diskName ? await (await root.getFileHandle(diskName)).getFile() : temporary;
    if (!iso && !disk) throw new Error('请选择启动 ISO 或 RAW 磁盘');
    if (disk && (!disk.size || disk.size % 512 !== 0)) throw new Error('RAW 磁盘大小必须是 512 字节的倍数');
    if (iso && (!iso.size || iso.size % 2048 !== 0)) throw new Error('ISO 大小必须是 2048 字节的倍数');
    const memory = saved?.memory ?? Number(get('memory').value);
    const focus = saved?.focus ?? get('focus').value;
    const state = saved ? await (await root.getFileHandle(saved.stateName)).getFile() : null;
    const screen = get('screen');
    screen.replaceChildren(document.createElement('div'), document.createElement('canvas'));
    const vm = new V86({
        wasm_path: '/v86.wasm',
        bios: { url: '/seabios.bin' },
        vga_bios: { url: '/vgabios.bin' },
        screen: { container: screen },
        memory_size: memory * 1024 ** 2,
        boot_order: iso ? 0x123 : 0x132,
        hda: disk ? image(disk) : undefined,
        cdrom: iso ? image(iso) : undefined,
        initial_state: state ? { buffer: state, async: false } : undefined,
        autostart: false,
        disable_speaker: true
    });
    session = { vm, diskName, memory, focus, ready: false, paused: false, hasDisc: !!iso };
    setupControls();
    report('正在加载来宾和固件…');
    try {
        await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('虚拟机启动超时')), 120000);
            function settle(action) {
                return value => {
                    clearTimeout(timeout);
                    action(value);
                };
            }
            vm.add_listener('emulator-ready', settle(resolve));
            vm.add_listener('download-error', settle(asset => reject(new Error('加载失败：' + asset.file_name))));
        });
    } catch (error) {
        await close();
        throw error;
    }
    session.ready = true;
    await syncFocus();
    report('来宾已就绪');
}

async function close() {
    const instance = session;
    session = null;
    await queue.catch(() => {});
    await instance.vm.destroy();
    get('keyboard-form').hidden = true;
    report('虚拟机已关闭');
}

async function save() {
    const current = session;
    if (!current?.ready || !current.diskName || current.hasDisc) return;
    const root = await storage();
    const previous = JSON.parse(localStorage.getItem(checkpointKey) || 'null');
    const stateName = 'state-' + crypto.randomUUID() + '.bin';
    current.checkpointing = true;
    let writer;
    try {
        await syncFocus();
        report('正在保存来宾内存和磁盘改动…');
        const buffer = await current.vm.save_state();
        const handle = await root.getFileHandle(stateName, { create: true });
        writer = await handle.createWritable();
        await writer.write(buffer);
        await writer.close();
        localStorage.setItem(checkpointKey, JSON.stringify({
            engine: engineVersion, diskName: current.diskName, memory: current.memory,
            focus: current.focus, stateName
        }));
        if (previous) await root.removeEntry(previous.stateName).catch(() => {});
        report('检查点已保存');
    } catch (error) {
        if (writer) await writer.abort().catch(() => {});
        await root.removeEntry(stateName).catch(() => {});
        throw error;
    } finally {
        current.checkpointing = false;
        await syncFocus();
    }
}

function eject() {
    session.vm.eject_cdrom();
    session.hasDisc = false;
    report('来宾光盘已弹出');
}

async function applyFilePolicy() {
    const mode = get('exchange-mode').value;
    if (mode === 'block') {
        eject();
        report('已阻止外部文件');
        return;
    }
    const selected = get('exchange-file').files[0];
    const payload = mode === 'custom' ? new Blob([get('custom').value]) : selected;
    if (!payload) throw new Error('请先选定要传入的文件');
    const name = mode === 'custom' ? 'INPUT.TXT' : /\.apk$/i.test(selected.name) ? 'INPUT.APK' : 'INPUT.BIN';
    await session.vm.set_cdrom(image(exchangeDisc(name, payload)));
    session.hasDisc = true;
    report('已挂载只读光盘 ' + name);
}

get('create-disk').onclick = () => safely(createDisk);
get('start').onclick = () => safely(() => boot(false));
get('restore').onclick = () => safely(() => boot(true));
get('reset').onclick = () => {
    if (confirm('关闭虚拟机？上次检查点之后的改动将丢失。')) safely(close);
};
get('save').onclick = () => safely(save);
get('pause').onclick = async () => {
    session.paused = !session.paused;
    await syncFocus().catch(error => report(String(error)));
};
get('exchange-mode').onchange = event => {
    get('custom-input').hidden = event.target.value !== 'custom';
    get('file-input').hidden = event.target.value !== 'file';
};
get('mount').onclick = () => safely(applyFilePolicy);
get('eject').onclick = () => safely(eject);
get('keyboard').onclick = () => {
    get('keyboard-form').hidden = !get('keyboard-form').hidden;
    if (!get('keyboard-form').hidden) get('typing').focus();
};
get('keyboard-form').onsubmit = event => {
    event.preventDefault();
    if (!hostFocus || !session?.vm.is_running()) return;
    const input = get('typing');
    if (/[^\x20-\x7e]/.test(input.value)) {
        report('键盘模拟只接受美式键盘字符。可用自定义光盘传递其他文字。');
        return;
    }
    session.vm.keyboard_send_text(input.value);
    input.value = '';
};
for (const button of document.querySelectorAll('[data-key]')) {
    button.onclick = () => {
        if (!hostFocus || !session?.vm.is_running()) return;
        const codes = button.dataset.key.split(',').map(Number);
        const keys = [];
        while (codes.length) keys.push(codes[0] === 224 ? codes.splice(0, 2) : codes.splice(0, 1));
        const release = keys.slice().reverse().flatMap(key => key.map((code, index) =>
            index === key.length - 1 ? code | 128 : code));
        session.vm.keyboard_send_scancodes([...keys.flat(), ...release]);
    };
}

setupControls();
loadDisks().catch(error => report(error.message));
