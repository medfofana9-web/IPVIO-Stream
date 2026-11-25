// Only the app version, the platform and the downloads bridge reach the page; everything else is plain web code.
const { contextBridge, ipcRenderer } = require('electron');
const v = (process.argv.find(a => a.startsWith('--fof-version=')) || '').split('=')[1] || '';
contextBridge.exposeInMainWorld('fof', {
  version: v,
  platform: process.platform,
  airplay: url => ipcRenderer.send('airplay', String(url)),
  downloads: {
    start: (url, title, id) => ipcRenderer.send('download', { url: String(url), title: String(title || ''), id: String(id || '') }),
    list: () => ipcRenderer.send('downloads'),
    cancel: key => ipcRenderer.send('download-cancel', String(key)),
    remove: key => ipcRenderer.send('download-delete', String(key)),
    show: key => ipcRenderer.send('download-show', String(key)),
    onChange: cb => ipcRenderer.on('downloads', (e, l) => cb(l))
  }
});
