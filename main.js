const { app, BrowserWindow, Menu, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');

const APP_ID = 'com.ridesync.editor';

// Zip entry names must stay inside the archive: no absolute paths, no "..".
function safeZipName(name, fallbackPath) {
    let s = String(name == null ? '' : name).replace(/\\/g, '/').replace(/^\/+/, '');
    if (!s || s.includes('..') || /^[a-zA-Z]:/.test(s)) s = path.basename(String(fallbackPath || ''));
    return s;
}

let mainWindow = null;
let isDirty = false;      // renderer reports unsaved changes
let allowClose = false;   // set once the user confirms quitting

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 1000,
        minHeight: 700,
        title: 'RideSync',
        backgroundColor: '#0b0b0d',
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false
        }
    });

    mainWindow.loadFile('index.html');

    // Unsaved-changes confirmation on close: Save / Don't Save / Cancel.
    mainWindow.on('close', async (e) => {
        if (allowClose || !isDirty) return;
        e.preventDefault();
        const { response } = await dialog.showMessageBox(mainWindow, {
            type: 'warning',
            buttons: ['Save', "Don't Save", 'Cancel'],
            defaultId: 0,
            cancelId: 2,
            noLink: true,
            title: 'Unsaved changes',
            message: 'Save changes before closing?',
            detail: 'Your timeline has unsaved changes.'
        });
        if (response === 0) {
            // Save: ask the renderer to run the save flow; it calls quitNow on success.
            mainWindow.webContents.send('rnrc:saveBeforeQuit');
        } else if (response === 1) {
            allowClose = true;
            mainWindow.close();
        }
        // response === 2 (Cancel): stay open.
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

ipcMain.on('rnrc:setDirty', (event, val) => { isDirty = !!val; });
ipcMain.on('rnrc:quitNow', () => {
    allowClose = true;
    if (mainWindow) mainWindow.close();
});

// ---------------------------------------------------------------------------
// ZIP packaging
// ---------------------------------------------------------------------------

function crc32(buf) {
    let table = crc32._table;
    if (!table) {
        table = crc32._table = new Int32Array(256);
        for (let n = 0; n < 256; n++) {
            let c = n;
            for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            table[n] = c;
        }
    }
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}

function buildZip(entries) {
    const now = new Date();
    const dosTime = ((now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1)) & 0xFFFF;
    const dosDate = (((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()) & 0xFFFF;

    const localParts = [];
    const centralParts = [];
    let offset = 0;
    const count = entries.length;

    for (const e of entries) {
        const data = e.data;
        const nameBuf = Buffer.from(e.name, 'utf8');
        const crc = crc32(data);

        let comp = data;
        let method = 0;
        if (data.length >= 100) {
            const def = zlib.deflateRawSync(data);
            if (def.length < data.length) { comp = def; method = 8; }
        }

        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);  // signature
        local.writeUInt16LE(20, 4);          // version needed
        local.writeUInt16LE(0x0800, 6);      // UTF-8 filename flag
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(dosTime, 10);
        local.writeUInt16LE(dosDate, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(comp.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(0, 28);          // extra len
        const localFull = Buffer.concat([local, nameBuf, comp]);
        localParts.push(localFull);

        const cen = Buffer.alloc(46);
        cen.writeUInt32LE(0x02014b50, 0);    // signature
        cen.writeUInt16LE(20, 4);            // version made by
        cen.writeUInt16LE(20, 6);            // version needed
        cen.writeUInt16LE(0x0800, 8);        // flags
        cen.writeUInt16LE(method, 10);
        cen.writeUInt16LE(dosTime, 12);
        cen.writeUInt16LE(dosDate, 14);
        cen.writeUInt32LE(crc, 16);
        cen.writeUInt32LE(comp.length, 20);
        cen.writeUInt32LE(data.length, 24);
        cen.writeUInt16LE(nameBuf.length, 28);
        cen.writeUInt16LE(0, 30);            // extra len
        cen.writeUInt16LE(0, 32);            // comment len
        cen.writeUInt16LE(0, 34);            // disk start
        cen.writeUInt16LE(0, 36);            // internal attrs
        cen.writeUInt32LE(0, 38);            // external attrs
        cen.writeUInt32LE(offset, 42);       // local header offset
        centralParts.push(Buffer.concat([cen, nameBuf]));

        offset += localFull.length;
    }

    const localBlob = Buffer.concat(localParts);
    const centralBlob = Buffer.concat(centralParts);

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);      // signature
    eocd.writeUInt16LE(0, 4);               // disk number
    eocd.writeUInt16LE(0, 6);               // disk with central dir
    eocd.writeUInt16LE(count, 8);           // entries on this disk
    eocd.writeUInt16LE(count, 10);          // total entries
    eocd.writeUInt32LE(centralBlob.length, 12);
    eocd.writeUInt32LE(localBlob.length, 16);
    eocd.writeUInt16LE(0, 20);              // comment len

    return Buffer.concat([localBlob, centralBlob, eocd]);
}

// Minimal ZIP reader (stored + deflate), enough to read our own project files.
function readZip(buf) {
    let eocd = -1;
    const min = Math.max(0, buf.length - 22 - 65536);
    for (let i = buf.length - 22; i >= min; i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('not a zip archive');
    const count = buf.readUInt16LE(eocd + 10);
    let off = buf.readUInt32LE(eocd + 16);
    const out = [];
    for (let n = 0; n < count; n++) {
        if (off + 46 > buf.length || buf.readUInt32LE(off) !== 0x02014b50) break;
        const method = buf.readUInt16LE(off + 10);
        const compSize = buf.readUInt32LE(off + 20);
        const nameLen = buf.readUInt16LE(off + 28);
        const extraLen = buf.readUInt16LE(off + 30);
        const commentLen = buf.readUInt16LE(off + 32);
        const localOff = buf.readUInt32LE(off + 42);
        const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
        const lnameLen = buf.readUInt16LE(localOff + 26);
        const lextraLen = buf.readUInt16LE(localOff + 28);
        const dataStart = localOff + 30 + lnameLen + lextraLen;
        const comp = buf.slice(dataStart, dataStart + compSize);
        let data;
        if (method === 0) data = comp;
        else if (method === 8) data = zlib.inflateRawSync(comp);
        else throw new Error('unsupported zip method ' + method);
        out.push({ name, data });
        off += 46 + nameLen + extraLen + commentLen;
    }
    return out;
}

ipcMain.handle('rnrc:package', async (event, payload) => {
    const binBytes = payload.binBytes || [];
    const files = payload.files || [];
    const entries = [{ name: 'config/timeline.bin', data: Buffer.from(binBytes) }];
    const missing = [];

    for (const f of files) {
        if (f.data != null) {
            const zn = safeZipName(f.name, f.path);
            if (!zn) { missing.push(f.name || 'unknown'); continue; }
            entries.push({ name: zn, data: Buffer.from(f.data) });
            continue;
        }
        if (f.content != null) {
            const zn = safeZipName(f.name, f.path);
            if (!zn) { missing.push(f.name || 'unknown'); continue; }
            entries.push({ name: zn, data: typeof f.content === 'string' ? Buffer.from(f.content, 'utf8') : Buffer.from(f.content) });
            continue;
        }
        const p = f.path;
        if (!p) { missing.push(f.name || 'unknown'); continue; }
        try {
            const data = fs.readFileSync(p);
            const zn = safeZipName(f.name, p);
            entries.push({ name: zn || path.basename(p), data });
        } catch (err) {
            missing.push(f.name || path.basename(p));
        }
    }

    const zip = buildZip(entries);

    const { canceled, filePath } = await dialog.showSaveDialog(mainWindow, {
        title: 'Export Audio Package (.zip)',
        defaultPath: path.join(app.getPath('documents'), 'timeline-package.zip'),
        filters: [{ name: 'ZIP Archive', extensions: ['zip'] }]
    });

    if (canceled || !filePath) return { canceled: true };

    try {
        fs.writeFileSync(filePath, zip);
    } catch (err) {
        return { canceled: false, error: 'Could not write package: ' + err.message };
    }
    return { canceled: false, filePath, missing };
});

ipcMain.handle('rnrc:pickAudioDir', async (event) => {
    const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow, {
        title: 'Select Audio Folder',
        properties: ['openDirectory']
    });
    if (canceled || !filePaths || !filePaths.length) return { canceled: true };
    return { canceled: false, dir: filePaths[0] };
});

ipcMain.handle('rnrc:gatherAudioFiles', async (event, payload) => {
    const dir = payload && payload.dir;
    if (!dir) return { files: [] };
    try {
        const names = fs.readdirSync(dir).filter(n => /\.(wav|ogg|mp3|m4a|aac|aif|aiff|flac|opus)$/i.test(n));
        return { files: names };
    } catch (err) {
        console.log('[audio] gatherAudioFiles error: ' + err.message);
        return { files: [] };
    }
});

ipcMain.handle('rnrc:readAudioFile', async (event, payload) => {
    const dir = payload && payload.dir;
    const name = payload && payload.name;
    if (!dir || !name) return { error: 'no path' };
    const safe = path.basename(name);
    const full = path.join(dir, safe);
    try {
        const buf = fs.readFileSync(full);
        const u8 = new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
        return { data: u8 };
    } catch (err) {
        console.log('[audio] readAudioFile ' + safe + ' error: ' + (err && err.message ? err.message : err));
        return { error: String(err && err.message ? err.message : err) };
    }
});

// ---------------------------------------------------------------------------
// EXPORT REGION AUDIO (.wav) - the renderer mixes the region and sends bytes
// ---------------------------------------------------------------------------
ipcMain.handle('rnrc:pathExists', async (event, payload) => {
    const paths = (payload && payload.paths) || [];
    return paths.map(p => {
        try { return fs.existsSync(p); } catch (e) { return false; }
    });
});

ipcMain.handle('rnrc:saveAudio', async (event, payload) => {
    const bytes = payload && payload.bytes;
    if (!bytes || !bytes.length) return { canceled: false, error: 'no audio data' };
    const suggested = path.basename(String((payload && payload.suggestedName) || 'region.wav'));
    const { canceled, filePath } = await dialog.showSaveDialog(mainWindow, {
        title: 'Export Region Audio',
        defaultPath: path.join(app.getPath('documents'), suggested),
        filters: [{ name: 'WAV audio', extensions: ['wav'] }]
    });
    if (canceled || !filePath) return { canceled: true };
    try {
        fs.writeFileSync(filePath, Buffer.from(bytes));
    } catch (err) {
        return { canceled: false, error: 'Could not write audio: ' + err.message };
    }
    return { canceled: false, filePath };
});

// ---------------------------------------------------------------------------
// PROJECT FILE (.nl2audio) - a zip with project.json + audio/<files>
// ---------------------------------------------------------------------------
ipcMain.handle('rnrc:saveProject', async (event, payload) => {
    const json = (payload && payload.json) || '{}';
    const files = (payload && payload.files) || [];
    const entries = [{ name: 'project.json', data: Buffer.from(json, 'utf8') }];
    const missing = [];

    for (const f of files) {
        const safe = path.basename(String(f.name || f.path || 'audio'));
        if (f.data) {
            entries.push({ name: 'audio/' + safe, data: Buffer.from(f.data) });
            continue;
        }
        const p = f.path;
        if (!p) { missing.push(f.name || 'unknown'); continue; }
        try {
            entries.push({ name: 'audio/' + safe, data: fs.readFileSync(p) });
        } catch (err) {
            missing.push(f.name || path.basename(p));
        }
    }

    const zip = buildZip(entries);
    const { canceled, filePath } = await dialog.showSaveDialog(mainWindow, {
        title: 'Save Project',
        defaultPath: path.join(app.getPath('documents'), 'timeline.nl2audio'),
        filters: [{ name: 'NL2 Audio Project', extensions: ['nl2audio'] }]
    });
    if (canceled || !filePath) return { canceled: true };
    try {
        fs.writeFileSync(filePath, zip);
    } catch (err) {
        return { canceled: false, error: 'Could not write project: ' + err.message };
    }
    return { canceled: false, filePath, missing };
});

ipcMain.handle('rnrc:openProject', async (event) => {
    const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow, {
        title: 'Open Project',
        filters: [{ name: 'NL2 Audio Project', extensions: ['nl2audio', 'zip'] }],
        properties: ['openFile']
    });
    if (canceled || !filePaths || !filePaths.length) return { canceled: true };
    try {
        const buf = fs.readFileSync(filePaths[0]);
        const entries = readZip(buf);
        let json = null;
        const files = [];
        for (const e of entries) {
            if (e.name === 'project.json') json = e.data.toString('utf8');
            else if (e.name.indexOf('audio/') === 0) {
                const name = e.name.slice(6);
                files.push({ name, data: new Uint8Array(e.data.buffer, e.data.byteOffset, e.data.byteLength) });
            }
        }
        if (json == null) return { canceled: false, error: 'project.json not found' };
        return { canceled: false, filePath: filePaths[0], json, files };
    } catch (err) {
        return { canceled: false, error: String(err && err.message ? err.message : err) };
    }
});

Menu.setApplicationMenu(null);

app.setAppUserModelId(APP_ID);

// Single instance: focus the existing window instead of opening a second one.
if (!app.requestSingleInstanceLock()) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.focus();
        }
    });

    app.whenReady().then(() => {
        createWindow();

        app.on('activate', () => {
            if (BrowserWindow.getAllWindows().length === 0) createWindow();
        });
    });

    app.on('window-all-closed', () => {
        if (process.platform !== 'darwin') app.quit();
    });
}

