// Copies the shared Fof TV screens (desktop/renderer) into www/ for the iPhone app and stamps the app version.
const fs = require('fs'), path = require('path');
const src = path.join(__dirname, '..', 'desktop', 'renderer'), out = path.join(__dirname, 'www');
fs.rmSync(out, { recursive: true, force: true });
fs.cpSync(src, out, { recursive: true });
const v = process.env.FOF_IOS_VERSION || require('./package.json').version;
const f = path.join(out, 'index.html');
const html = fs.readFileSync(f, 'utf8');
if (!html.includes('<script src="app.js">')) throw new Error('index.html changed: app.js script tag not found');
// boot.js loads app.js (or a newer one downloaded from GitHub, see boot.js)
fs.copyFileSync(path.join(__dirname, 'boot.js'), path.join(out, 'boot.js'));
fs.writeFileSync(f, html.replace('<script src="app.js"></script>', `<script>window.FOF_IOS_VERSION=${JSON.stringify(v)};window.FOF_BUILD_TIME=${Date.now()}</script>\n<script src="boot.js"></script>`));
console.log('www ready, version', v);
