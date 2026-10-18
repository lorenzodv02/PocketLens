const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("pocketLensCamera", {
  getStatus: () => ipcRenderer.invoke("virtual-camera:status"),
  install: () => ipcRenderer.invoke("virtual-camera:install"),
  start: () => ipcRenderer.invoke("virtual-camera:start"),
  stop: () => ipcRenderer.invoke("virtual-camera:stop")
});
