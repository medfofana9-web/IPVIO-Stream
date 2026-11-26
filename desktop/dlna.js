// Fof TV for Mac – send to smart TVs (Samsung, LG, Sony…) over the home Wi-Fi with UPnP/DLNA.
// The TV's own media player fetches and plays the stream itself (it handles .mkv / HEVC / live .ts), so the Mac can be
// closed afterwards. Discovery: SSDP M-SEARCH for MediaRenderer devices; control: AVTransport SOAP actions.
'use strict';
const dgram = require('dgram');

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));
const tag = (xml, name) => { const m = new RegExp(`<(?:\\w+:)?${name}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${name}>`, 'i').exec(xml); return m ? m[1].trim() : ''; };
let found = new Map();   // location -> device

async function describe(location, ms = 4000) {
  const r = await fetch(location, { signal: AbortSignal.timeout(ms) });
  if (!r.ok) return null;
  const xml = await r.text();
  const base = tag(xml, 'URLBase') || location;
  const svc = type => {
    const re = /<service>([\s\S]*?)<\/service>/gi; let m;
    while ((m = re.exec(xml))) if (new RegExp(type, 'i').test(tag(m[1], 'serviceType'))) return new URL(tag(m[1], 'controlURL'), base).toString();
    return '';
  };
  const av = svc('AVTransport');
  if (!av) return null;
  return { id: location, name: tag(xml, 'friendlyName') || 'TV', maker: tag(xml, 'manufacturer'), model: tag(xml, 'modelName'), av, rc: svc('RenderingControl') };
}

// Fallback when the network drops multicast (mesh Wi-Fi, some routers): ask every address of this Mac's subnet for the
// renderer description on the ports Samsung (9197 /dmr, 7676) and others use
async function scan() {
  const os = require('os'); const bases = new Set();
  for (const l of Object.values(os.networkInterfaces())) for (const i of l || []) if (i.family === 'IPv4' && !i.internal && /^(10|172|192)\./.test(i.address)) bases.add(i.address.split('.').slice(0, 3).join('.'));
  const urls = [];
  for (const b of bases) for (let n = 1; n < 255; n++) for (const path of [':9197/dmr', ':7676/smp_15_', ':1400/xml/device_description.xml', ':49152/description.xml']) urls.push(`http://${b}.${n}${path}`);
  const out = []; let i = 0;
  const worker = async () => { while (i < urls.length) { const u = urls[i++]; try { const d = await describe(u, 1200); if (d && !out.some(x => x.name === d.name)) { found.set(d.id, d); out.push(d); } } catch (e) {} } };
  await Promise.all(Array.from({ length: 48 }, worker));
  return out;
}

async function discoverAll() {
  const l = await discover();
  if (l.length) return l;
  return (await scan()).sort((a, b) => a.name.localeCompare(b.name));
}

function discover(ms = 3500) {
  return new Promise(resolve => {
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const locs = new Set();
    const msg = Buffer.from(['M-SEARCH * HTTP/1.1', 'HOST: 239.255.255.250:1900', 'MAN: "ssdp:discover"', 'MX: 2', 'ST: urn:schemas-upnp-org:device:MediaRenderer:1', '', ''].join('\r\n'));
    sock.on('message', b => { const m = /^location:\s*(.+)$/im.exec(b.toString()); if (m) locs.add(m[1].trim()); });
    sock.on('error', () => {});
    sock.bind(() => { try { sock.setBroadcast(true); } catch (e) {} for (const d of [0, 800, 1800]) setTimeout(() => sock.send(msg, 1900, '239.255.255.250', () => {}), d); });
    setTimeout(async () => {
      try { sock.close(); } catch (e) {}
      const out = [];
      await Promise.all([...locs].map(async l => { try { const d = await describe(l); if (d) { found.set(d.id, d); out.push(d); } } catch (e) {} }));
      resolve(out.sort((a, b) => a.name.localeCompare(b.name)));
    }, ms);
  });
}

async function soap(url, service, action, args) {
  const body = `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action} xmlns:u="urn:schemas-upnp-org:service:${service}:1">${Object.entries(args).map(([k, v]) => `<${k}>${esc(v)}</${k}>`).join('')}</u:${action}></s:Body></s:Envelope>`;
  const r = await fetch(url, { method: 'POST', body, headers: { 'Content-Type': 'text/xml; charset="utf-8"', SOAPACTION: `"urn:schemas-upnp-org:service:${service}:1#${action}"` }, signal: AbortSignal.timeout(8000) });
  const txt = await r.text();
  if (!r.ok) throw new Error(`${action}: ${tag(txt, 'errorDescription') || 'HTTP ' + r.status}`);
  return txt;
}

const mimeOf = u => { const p = String(u).split('?')[0].toLowerCase();
  return p.endsWith('.mkv') ? 'video/x-matroska' : p.endsWith('.ts') ? 'video/mp2t' : p.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl'
    : p.endsWith('.avi') ? 'video/x-msvideo' : p.endsWith('.webm') ? 'video/webm' : 'video/mp4'; };
const hms = s => { s = Math.max(0, Math.floor(s)); return [Math.floor(s / 3600), Math.floor(s % 3600 / 60), s % 60].map(n => String(n).padStart(2, '0')).join(':'); };
const secs = t => { const m = /(\d+):(\d+):(\d+)/.exec(t || ''); return m ? +m[1] * 3600 + +m[2] * 60 + +m[3] : 0; };

async function dev(id) { return found.get(id) || describe(id).then(d => { if (d) found.set(id, d); return d; }); }

// Relay: some providers refuse a TV's own player (no "VLC" user agent, no HEAD request) -> the TV answers
// "Resource not found". Then the Mac fetches the stream and passes it on to the TV over the Wi-Fi (the Mac has to stay
// awake for that title). Range requests are passed through, so seeking works for movies.
const http = require('http'), https = require('https');
let relay = null; const relayed = new Map(); let nextId = 1;
// Keep-alive upstream connections: the TV sends many Range requests while it starts a video.
const kaHttp = new http.Agent({ keepAlive: true, maxSockets: 8 }), kaHttps = new https.Agent({ keepAlive: true, maxSockets: 8 });
// Activity, so the app can stay awake while a TV is pulling a stream through this Mac.
let openConns = 0, lastData = 0;
const busy = () => openConns > 0 || Date.now() - lastData < 90000;
// Hosts the TV could not play directly: next time go straight to the relay (saves 10-20 s of failed tries).
const hostsFile = (() => { try { return require('path').join(require('electron').app.getPath('userData'), 'relay-hosts.json'); } catch (e) { return null; } })();
const relayHosts = new Set((() => { try { return JSON.parse(require('fs').readFileSync(hostsFile, 'utf8')); } catch (e) { return []; } })());
const saveHosts = () => { try { require('fs').writeFileSync(hostsFile, JSON.stringify([...relayHosts])); } catch (e) {} };
const hostOf = u => { try { return new URL(u).host; } catch (e) { return ''; } };
function lanIp() { const os = require('os'); for (const l of Object.values(os.networkInterfaces())) for (const i of l || []) if (i.family === 'IPv4' && !i.internal && /^(10|172|192)\./.test(i.address)) return i.address; return '127.0.0.1'; }
function relayStart() {
  if (relay) return Promise.resolve(relay);
  return new Promise(res => {
    const srv = http.createServer((req, out) => {
      const m = /^\/r\/(\d+)/.exec(req.url || ''); const src = m && relayed.get(+m[1]);
      if (!src) { out.writeHead(404); return out.end(); }
      openConns++; let done = false; const fin = () => { if (!done) { done = true; openConns--; lastData = Date.now(); } };
      out.on('close', fin);
      const get = (u, hops) => {
        const lib = u.startsWith('https') ? https : http;
        const up = lib.request(u, { agent: u.startsWith('https') ? kaHttps : kaHttp, method: req.method === 'HEAD' ? 'GET' : 'GET', headers: Object.assign({ 'User-Agent': 'VLC/3.0.21 LibVLC/3.0.21', Accept: '*/*' }, req.headers.range ? { Range: req.headers.range } : {}) }, r => {
          if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location && hops < 5) { r.resume(); return get(new URL(r.headers.location, u).toString(), hops + 1); }
          if (r.statusCode < 300 && !src.live) src.final = u; // later Range requests skip the redirects (not for live: tokens expire)
          const h = { 'Content-Type': src.mime, 'Accept-Ranges': 'bytes', 'transferMode.dlna.org': 'Streaming', 'contentFeatures.dlna.org': 'DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000' };
          for (const k of ['content-length', 'content-range']) if (r.headers[k]) h[k] = r.headers[k];
          out.writeHead(r.statusCode === 206 ? 206 : 200, h);
          if (req.method === 'HEAD') { r.destroy(); return out.end(); }
          r.on('data', () => { lastData = Date.now(); });
          r.pipe(out);
          out.on('close', () => r.destroy());
        });
        up.on('error', () => { try { out.writeHead(502); out.end(); } catch (e) {} });
        out.on('close', () => up.destroy());
        up.end();
      };
      get(src.final || src.url, 0);
    });
    srv.listen(0, '0.0.0.0', () => { relay = { port: srv.address().port }; res(relay); });
  });
}
async function relayUrl(url) {
  const r = await relayStart(); const id = nextId++;
  relayed.set(id, { url, mime: mimeOf(url), live: /\/live\//.test(url) });
  if (relayed.size > 20) relayed.delete([...relayed.keys()][0]);
  return `http://${lanIp()}:${r.port}/r/${id}`;
}

async function playOne(d, url, title, mime) {
  const didl = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(title || 'Fof TV')}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${mime}:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000">${esc(url)}</res></item></DIDL-Lite>`;
  try { await soap(d.av, 'AVTransport', 'Stop', { InstanceID: 0 }); } catch (e) {}
  await soap(d.av, 'AVTransport', 'SetAVTransportURI', { InstanceID: 0, CurrentURI: url, CurrentURIMetaData: didl });
  await soap(d.av, 'AVTransport', 'Play', { InstanceID: 0, Speed: 1 });
}

// Seek once the TV is actually playing (a fixed delay either seeks too early and is ignored, or makes the start slow).
async function seekWhenPlaying(d, start) {
  for (let i = 0; i < 40; i++) {
    await new Promise(r => setTimeout(r, 750));
    let st = ''; try { st = tag(await soap(d.av, 'AVTransport', 'GetTransportInfo', { InstanceID: 0 }), 'CurrentTransportState'); } catch (e) {}
    if (st === 'PLAYING') { try { await soap(d.av, 'AVTransport', 'Seek', { InstanceID: 0, Unit: 'REL_TIME', Target: hms(start) }); } catch (e) {} return; }
  }
}

async function play(id, url, title, start) {
  const d = await dev(id); if (!d) throw new Error('TV not found on the Wi-Fi');
  const live = /\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/.test(url);
  const host = hostOf(url);
  let relayed = relayHosts.has(host), last = null;
  if (!relayed) {
    const tries = [[url, mimeOf(url)]];
    if (live) tries.push([url.replace(/\.ts(\?|$)/, '.m3u8$1'), 'application/vnd.apple.mpegurl']);
    for (const [u, mime] of tries) { try { await playOne(d, u, title, mime); last = null; break; } catch (e) { last = e; } }
    if (last) { relayHosts.add(host); saveHosts(); relayed = true; }
  }
  if (relayed) await playOne(d, await relayUrl(url), title, mimeOf(url));
  if (start > 5 && !live) seekWhenPlaying(d, start);
  return { ok: true, relayed };
}

async function control(id, c, v) {
  const d = await dev(id); if (!d) throw new Error('TV not found on the Wi-Fi');
  if (c === 'pause') {
    const st = tag(await soap(d.av, 'AVTransport', 'GetTransportInfo', { InstanceID: 0 }), 'CurrentTransportState');
    return st === 'PLAYING' ? soap(d.av, 'AVTransport', 'Pause', { InstanceID: 0 }) : soap(d.av, 'AVTransport', 'Play', { InstanceID: 0, Speed: 1 });
  }
  if (c === 'stop') return soap(d.av, 'AVTransport', 'Stop', { InstanceID: 0 });
  if (c === 'skip') {
    const pos = secs(tag(await soap(d.av, 'AVTransport', 'GetPositionInfo', { InstanceID: 0 }), 'RelTime'));
    return soap(d.av, 'AVTransport', 'Seek', { InstanceID: 0, Unit: 'REL_TIME', Target: hms(pos + (+v || 0)) });
  }
  if (c === 'vol' && d.rc) {
    const cur = +tag(await soap(d.rc, 'RenderingControl', 'GetVolume', { InstanceID: 0, Channel: 'Master' }), 'CurrentVolume') || 0;
    return soap(d.rc, 'RenderingControl', 'SetVolume', { InstanceID: 0, Channel: 'Master', DesiredVolume: Math.max(0, Math.min(100, cur + (+v > 0 ? 2 : -2))) });
  }
}

async function position(id) {
  const d = await dev(id); if (!d) return null;
  const x = await soap(d.av, 'AVTransport', 'GetPositionInfo', { InstanceID: 0 });
  return { pos: secs(tag(x, 'RelTime')), dur: secs(tag(x, 'TrackDuration')) };
}

module.exports = { discover: discoverAll, play, control, position, busy };
