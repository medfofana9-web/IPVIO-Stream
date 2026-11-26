// Only the app version, the platform and the downloads bridge reach the page; everything else is plain web code.
const { contextBridge, ipcRenderer } = require('electron');
const v = (process.argv.find(a => a.startsWith('--fof-version=')) || '').split('=')[1] || '';
contextBridge.exposeInMainWorld('fof', {
  version: v,
  platform: process.platform,
  airplay: url => ipcRenderer.invoke('airplay', String(url)),
  smartTv: {
    find: () => ipcRenderer.invoke('tvs'),
    play: (id, url, title, start) => ipcRenderer.invoke('tvplay', { id: String(id), url: String(url), title: String(title || ''), start: +start || 0 }),
    control: (id, c, v) => ipcRenderer.invoke('tvctl', { id: String(id), c: String(c), v: String(v || '') }),
    position: id => ipcRenderer.invoke('tvpos', String(id))
  },
  downloads: {
    start: (url, title, id) => ipcRenderer.send('download', { url: String(url), title: String(title || ''), id: String(id || '') }),
    list: () => ipcRenderer.send('downloads'),
    cancel: key => ipcRenderer.send('download-cancel', String(key)),
    remove: key => ipcRenderer.send('download-delete', String(key)),
    show: key => ipcRenderer.send('download-show', String(key)),
    onChange: cb => ipcRenderer.on('downloads', (e, l) => cb(l))
  }
});
