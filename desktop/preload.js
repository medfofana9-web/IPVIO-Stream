// Only the app version and platform reach the page; everything else is plain web code.
const { contextBridge } = require('electron');
const v = (process.argv.find(a => a.startsWith('--fof-version=')) || '').split('=')[1] || '';
contextBridge.exposeInMainWorld('fof', { version: v, platform: process.platform });
