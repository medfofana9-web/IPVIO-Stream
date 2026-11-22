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

// A new outer shell (rare): tell once per shell number, with the download page
async function checkShell() {
  const pkg = await fetch(`https://raw.githubusercontent.com/${REPO}/main/desktop/package.json`).then(r => r.json());
  const remote = pkg.shell || 1;
  const told = path.join(app.getPath('userData'), 'shell-told');
  const last = fs.existsSync(told) ? +fs.readFileSync(told, 'utf8') : 0;
  if (remote > SHELL && remote !== last) {
    fs.writeFileSync(told, String(remote));
    const r = await dialog.showMessageBox(win, { type: 'info', buttons: ['Download', 'Later'], defaultId: 0, message: 'A new Fof TV for Mac is ready',
      detail: 'This update changes the app itself, so it needs a new download (one time). Everything else updates on its own.' });
    if (r.response === 0) shell.openExternal(`https://github.com/${REPO}/releases`);
  }
}

async function checkUpdates(manual) {
  try {
    await updateRenderer();
    // Newer screens than the ones on show (just downloaded, or downloaded earlier while something was playing)
    if (win && rendererDir() !== loadedDir) {
      const playing = await win.webContents.executeJavaScript("!document.getElementById('player').classList.contains('hidden')").catch(() => true);
      if (!playing) loadScreens();
      else if (manual) dialog.showMessageBox(win, { message: 'An update is ready', detail: 'It starts as soon as you close the player.' });
    } else if (manual && win) dialog.showMessageBox(win, { message: 'Fof TV is up to date.' });
    await checkShell();
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
