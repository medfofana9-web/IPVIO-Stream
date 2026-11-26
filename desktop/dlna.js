// Fof TV for Mac – send to smart TVs (Samsung, LG, Sony…) over the home Wi-Fi with UPnP/DLNA.
// The TV's own media player fetches and plays the stream itself (it handles .mkv / HEVC / live .ts), so the Mac can be
// closed afterwards. Discovery: SSDP M-SEARCH for MediaRenderer devices; control: AVTransport SOAP actions.
'use strict';
const dgram = require('dgram');

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]));
const tag = (xml, name) => { const m = new RegExp(`<(?:\\w+:)?${name}[^>]*>([\\s\\S]*?)</(?:\\w+:)?${name}>`, 'i').exec(xml); return m ? m[1].trim() : ''; };
let found = new Map();   // location -> device

async function describe(location) {
  const r = await fetch(location, { signal: AbortSignal.timeout(4000) });
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

async function play(id, url, title, start) {
  const d = await dev(id); if (!d) throw new Error('TV not found on the Wi-Fi');
  const mime = mimeOf(url);
  const didl = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${esc(title || 'Fof TV')}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${mime}:*">${esc(url)}</res></item></DIDL-Lite>`;
  try { await soap(d.av, 'AVTransport', 'Stop', { InstanceID: 0 }); } catch (e) {}
  await soap(d.av, 'AVTransport', 'SetAVTransportURI', { InstanceID: 0, CurrentURI: url, CurrentURIMetaData: didl });
  await soap(d.av, 'AVTransport', 'Play', { InstanceID: 0, Speed: 1 });
  if (start > 5) setTimeout(() => soap(d.av, 'AVTransport', 'Seek', { InstanceID: 0, Unit: 'REL_TIME', Target: hms(start) }).catch(() => {}), 4000);
  return { ok: true };
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

module.exports = { discover, play, control, position };
