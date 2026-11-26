// Fof TV for Mac – main process: one window, Mac menu, and self-updating screens.
//
// Self-update: the screens and features (desktop/renderer) are downloaded from the newest commit on GitHub into the
// app's own folder and used right away (or from the next start while something is playing), so most changes never
// need a new download. Only this outer shell (main.js, preload.js) needs a reinstall; when desktop/package.json on
// GitHub has a higher "shell" number, the app says so once and links the download page.
//
// Streams from IPTV providers are often plain http and send no CORS headers, so web security is off for this
// private player window (it only loads the app's own files).
const { app, BrowserWindow, Menu, shell, session, dialog, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');

const REPO = 'medfofana9-web/IPVIO-Stream';
const SHELL = require('./package.json').shell || 1;
const BUNDLED = path.join(__dirname, 'renderer');
const CACHE = () => path.join(app.getPath('userData'), 'renderer');
let win = null;
let loadedDir = null;   // screens the window shows now (an update downloaded while playing waits for the next load)
function loadScreens() { loadedDir = rendererDir(); win.loadFile(path.join(loadedDir, 'index.html')); }

// Newest complete downloaded screens, else the ones inside the app
function rendererDir() {
  try {
    const cur = fs.readFileSync(path.join(CACHE(), 'current'), 'utf8').trim();
    const dir = path.join(CACHE(), cur);
    if (cur && fs.existsSync(path.join(dir, '.complete')) && fs.existsSync(path.join(dir, 'index.html'))) return dir;
  } catch (e) {}
  return BUNDLED;
}

async function getJson(url) {
  const r = await fetch(url, { headers: { 'User-Agent': 'FofTV-Mac', Accept: 'application/vnd.github+json' } });
  if (!r.ok) throw new Error(url + ' ' + r.status);
  return r.json();
}

// Downloads desktop/renderer from the newest commit that changed it. Returns true when a new version is ready.
async function updateRenderer() {
  const commits = await getJson(`https://api.github.com/repos/${REPO}/commits?path=desktop/renderer&per_page=1`);
  const sha = commits && commits[0] && commits[0].sha;
  if (!sha) return false;
  const id = sha.slice(0, 12);
  const dir = path.join(CACHE(), id);
  const before = rendererDir();
  if (fs.existsSync(path.join(dir, '.complete'))) {
    fs.writeFileSync(path.join(CACHE(), 'current'), id);
    return before !== dir;
  }
  const tree = await getJson(`https://api.github.com/repos/${REPO}/git/trees/${sha}?recursive=1`);
  const files = (tree.tree || []).filter(t => t.type === 'blob' && t.path.startsWith('desktop/renderer/'));
  if (!files.some(f => f.path === 'desktop/renderer/index.html')) return false;
  for (const f of files) {
    const rel = f.path.slice('desktop/renderer/'.length);
    if (rel.split('/').includes('..')) continue;
    const r = await fetch(`https://raw.githubusercontent.com/${REPO}/${sha}/${f.path}`);
    if (!r.ok) throw new Error('download ' + f.path + ' ' + r.status);
    const out = path.join(dir, rel);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, Buffer.from(await r.arrayBuffer()));
  }
  fs.writeFileSync(path.join(dir, '.complete'), new Date().toISOString());
  fs.writeFileSync(path.join(CACHE(), 'current'), id);
  // Keep only this version and the one in use before
  try {
    const keep = new Set(['current', id, path.basename(before)]);
    for (const d of fs.readdirSync(CACHE())) if (!keep.has(d)) fs.rmSync(path.join(CACHE(), d), { recursive: true, force: true });
  } catch (e) {}
  return true;
}

// A new outer shell (rare): the app replaces itself. It downloads the new app from the GitHub release of that version,
// unpacks it next to itself, and after it quits a small script swaps the app in /Applications, signs it for this Mac
// (ad hoc, the same as the install command) and opens it again. A file the app downloads itself is not quarantined, so
// macOS does not ask again. Falls back to the download page only when the app cannot write to its own folder.
const { spawn, execFileSync } = require('child_process');
const os = require('os');
let shellReady = null;   // { app: path of the unpacked new app, version }
function appBundle() {
  const exe = app.getPath('exe');                       // …/Fof TV.app/Contents/MacOS/Fof TV
  const i = exe.indexOf('.app/Contents/MacOS/');
  return i > 0 ? exe.slice(0, i + 4) : null;
}
async function downloadShell(version) {
  const rels = await getJson(`https://api.github.com/repos/${REPO}/releases?per_page=30`);
  const rel = (rels || []).find(r => String(r.tag_name || '').startsWith('mac-' + version + '-'));
  const arch = process.arch === 'arm64' ? 'arm64' : 'x64';
  const asset = rel && (rel.assets || []).find(a => a.name.endsWith('-' + arch + '.zip'));
  if (!asset) return null;                              // not built yet: tried again at the next check
  const dir = path.join(app.getPath('userData'), 'shell-update', version);
  const done = path.join(dir, '.ready');
  const unpacked = () => (fs.readdirSync(dir).find(n => n.endsWith('.app')));
  if (fs.existsSync(done) && unpacked()) return path.join(dir, unpacked());
  fs.rmSync(path.join(app.getPath('userData'), 'shell-update'), { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const r = await fetch(asset.browser_download_url);
  if (!r.ok) throw new Error('download ' + r.status);
  const zip = path.join(dir, 'app.zip');
  fs.writeFileSync(zip, Buffer.from(await r.arrayBuffer()));
  execFileSync('/usr/bin/ditto', ['-x', '-k', zip, dir]);
  fs.unlinkSync(zip);
  const a = unpacked(); if (!a) throw new Error('no app in the download');
  fs.writeFileSync(done, version);
  return path.join(dir, a);
}
function installShell(newApp) {
  const target = appBundle();
  if (!target) return false;
  try { fs.accessSync(path.dirname(target), fs.constants.W_OK); } catch (e) { return false; }
  const script = path.join(os.tmpdir(), 'foftv-update.sh');
  const q = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
  fs.writeFileSync(script, `#!/bin/sh
while kill -0 ${process.pid} 2>/dev/null; do sleep 0.3; done
rm -rf ${q(target)}.old
mv ${q(target)} ${q(target)}.old && mv ${q(newApp)} ${q(target)} || { mv ${q(target)}.old ${q(target)}; open ${q(target)}; exit 1; }
xattr -cr ${q(target)}
codesign --force --deep --sign - ${q(target)} >/dev/null 2>&1
rm -rf ${q(target)}.old
open ${q(target)}
`);
  fs.chmodSync(script, 0o755);
  spawn('/bin/sh', [script], { detached: true, stdio: 'ignore' }).unref();
  app.quit();
  return true;
}
async function checkShell(manual) {
  const pkg = await fetch(`https://raw.githubusercontent.com/${REPO}/main/desktop/package.json`).then(r => r.json());
  if ((pkg.shell || 1) <= SHELL) return false;
  const newApp = await downloadShell(pkg.version);
  if (!newApp) return false;
  shellReady = { app: newApp, version: pkg.version };
  const playing = win ? await win.webContents.executeJavaScript("!document.getElementById('player').classList.contains('hidden')").catch(() => true) : false;
  // Nothing playing: restart into the new app at once (a few seconds); while something plays: when the app quits
  if (!playing || manual) {
    const r = playing ? await dialog.showMessageBox(win, { type: 'info', buttons: ['Restart Now', 'Later'], defaultId: 0, message: 'Fof TV ' + pkg.version + ' is ready', detail: 'It restarts in a few seconds and picks up where you are.' }) : { response: 0 };
    if (r.response === 0 && !installShell(newApp)) shell.openExternal(`https://github.com/${REPO}/releases`);
  }
  return true;
}
// Quitting with a new app waiting: install it on the way out
app.on('before-quit', e => {
  if (!shellReady || shellReady.installing) return;
  shellReady.installing = true;
  const target = appBundle();
  if (target) { e.preventDefault(); if (!installShell(shellReady.app)) app.exit(0); }
});

async function checkUpdates(manual) {
  try {
    await updateRenderer();
    // Newer screens than the ones on show (just downloaded, or downloaded earlier while something was playing)
    if (win && rendererDir() !== loadedDir) {
      const playing = await win.webContents.executeJavaScript("!document.getElementById('player').classList.contains('hidden')").catch(() => true);
      if (!playing) loadScreens();
      else if (manual) dialog.showMessageBox(win, { message: 'An update is ready', detail: 'It starts as soon as you close the player.' });
    } else if (manual && win) dialog.showMessageBox(win, { message: 'Fof TV is up to date.' });
    await checkShell(manual);
  } catch (e) {
    if (manual && win) dialog.showMessageBox(win, { type: 'warning', message: 'Could not check for updates', detail: String(e.message || e) });
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: 1440, height: 900, minWidth: 1024, minHeight: 640,
    backgroundColor: '#000000',
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 18, y: 18 },
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      sandbox: false,
      additionalArguments: ['--fof-version=' + app.getVersion()],
      webSecurity: false,
      backgroundThrottling: false
    }
  });
  loadScreens();
  win.once('ready-to-show', () => win.show());
  // Links to web pages open in the normal browser, never inside the player window
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file://')) { e.preventDefault(); shell.openExternal(url); } });
  // A downloaded version that fails to load falls back to the one inside the app
  win.webContents.on('did-fail-load', () => {
    if (rendererDir() !== BUNDLED) { try { fs.unlinkSync(path.join(CACHE(), 'current')); } catch (e) {} loadScreens(); }
  });
}

app.whenReady().then(() => {
  // Some providers only answer a "normal" player
  session.defaultSession.setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36 FofTV/' + app.getVersion());
  const isMac = process.platform === 'darwin';
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    ...(isMac ? [{ label: app.name, submenu: [{ role: 'about' }, { label: 'Check for Updates…', click: () => checkUpdates(true) }, { type: 'separator' }, { role: 'hide' }, { role: 'hideOthers' }, { role: 'unhide' }, { type: 'separator' }, { role: 'quit' }] }] : []),
    { role: 'editMenu' },
    { label: 'View', submenu: [{ role: 'reload' }, { role: 'togglefullscreen' }, { type: 'separator' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' }, { role: 'toggleDevTools' }] },
    { role: 'windowMenu' },
    { role: 'help', submenu: [{ label: 'Fof TV on GitHub', click: () => shell.openExternal('https://github.com/' + REPO) }] }
  ]));
  // YouTube trailers refuse to play in an embed without a web page as referrer (the app's pages are local files)
  session.defaultSession.webRequest.onBeforeSendHeaders({ urls: ['*://*.youtube-nocookie.com/*', '*://*.youtube.com/*', '*://*.googlevideo.com/*', '*://*.ytimg.com/*'] }, (d, cb) => {
    d.requestHeaders.Referer = 'https://fof.tv/'; cb({ requestHeaders: d.requestHeaders });
  });
  setupDownloads();
  createWindow();
  // Check at start and every 2 hours while the app is open
  setTimeout(() => checkUpdates(false), 4000);
  setInterval(() => checkUpdates(false), 2 * 60 * 60 * 1000);
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

// ------------------------------------------------------------------ downloads (watch offline)
// Saved to ~/Movies/Fof TV; the list (title, file, size, progress) lives in the app's folder and is shown on the
// Downloads page. Only direct links (mp4/mkv/…) download; live channels and HLS playlists are not files.
const DL_DIR = () => path.join(app.getPath('videos'), 'Fof TV');
const DL_LIST = () => path.join(app.getPath('userData'), 'downloads.json');
let downloads = [];
const pending = [];
function saveDownloads() { try { fs.writeFileSync(DL_LIST(), JSON.stringify(downloads.map(d => Object.assign({}, d, { item: undefined })))); } catch (e) {} }
function sendDownloads() { if (win) win.webContents.send('downloads', downloads.map(d => Object.assign({}, d, { item: undefined }))); }
function setupDownloads() {
  try { downloads = JSON.parse(fs.readFileSync(DL_LIST(), 'utf8')).filter(d => d.state === 'done' && fs.existsSync(d.file)); } catch (e) { downloads = []; }
  session.defaultSession.on('will-download', (e, item) => {
    const meta = pending.shift() || { title: item.getFilename(), id: '' };
    fs.mkdirSync(DL_DIR(), { recursive: true });
    const ext = (path.extname(item.getFilename()) || '.mp4').slice(0, 6);
    const safe = meta.title.replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120) || 'Download';
    let file = path.join(DL_DIR(), safe + ext); let n = 2;
    while (fs.existsSync(file)) file = path.join(DL_DIR(), `${safe} (${n++})${ext}`);
    item.setSavePath(file);
    const d = { key: Date.now() + '-' + Math.random().toString(36).slice(2, 7), title: meta.title, id: meta.id, file, size: item.getTotalBytes(), got: 0, state: 'running', at: Date.now(), item };
    downloads.unshift(d); sendDownloads();
    let last = 0;
    item.on('updated', () => { d.got = item.getReceivedBytes(); d.size = item.getTotalBytes(); if (Date.now() - last > 700) { last = Date.now(); sendDownloads(); } });
    item.once('done', (ev, state) => { d.state = state === 'completed' ? 'done' : 'failed'; d.got = item.getReceivedBytes(); delete d.item; saveDownloads(); sendDownloads(); });
  });
  // AirPlay: Chrome's video engine cannot AirPlay, Apple's QuickTime Player can. The stream opens there and its
  // AirPlay button lists the same TVs as the iPhone (Fire TV, Samsung, Apple TV). http(s) links only.
  ipcMain.handle('airplay', async (e, url) => {
    url = String(url || '');
    if (!/^https?:\/\/[^\s"']+$/.test(url) || process.platform !== 'darwin') return { ok: false, err: 'not supported here' };
    const { execFile } = require('child_process');
    const run = (cmd, args) => new Promise(res => execFile(cmd, args, { timeout: 20000 }, (err, out, se) => res(err ? String(se || err.message).trim() : '')));
    // 1) QuickTime Player (its AirPlay button lists the TVs)
    const qt = `tell application "QuickTime Player"\nactivate\nset d to open URL "${url}"\ndelay 2\ntry\nplay d\nend try\nend tell`;
    let err = await run('osascript', ['-e', qt]);
    if (!err) return { ok: true, app: 'QuickTime' };
    // 2) Safari plays HLS itself and has AirPlay in its video controls
    const err2 = await run('open', ['-a', 'Safari', url]);
    if (!err2) return { ok: true, app: 'Safari' };
    return { ok: false, err: err.slice(0, 160) };
  });
  ipcMain.on('download', (e, { url, title, id }) => { pending.push({ title, id }); win.webContents.downloadURL(url); });
  ipcMain.on('downloads', () => sendDownloads());
  ipcMain.on('download-cancel', (e, key) => { const d = downloads.find(x => x.key === key); if (d && d.item) d.item.cancel(); });
  ipcMain.on('download-delete', (e, key) => {
    const d = downloads.find(x => x.key === key); if (!d) return;
    if (d.item) d.item.cancel();
    try { fs.unlinkSync(d.file); } catch (x) {}
    downloads = downloads.filter(x => x !== d); saveDownloads(); sendDownloads();
  });
  ipcMain.on('download-show', (e, key) => { const d = downloads.find(x => x.key === key); if (d) shell.showItemInFolder(d.file); });
}

app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
