const { contextBridge, ipcRenderer, webUtils } = require('electron');

contextBridge.exposeInMainWorld('rnrc', {
    getPathForFile: (file) => {
        try {
            if (webUtils && webUtils.getPathForFile) return webUtils.getPathForFile(file);
        } catch (_) { /* fall through */ }
        return (file && file.path) ? file.path : null;
    },
    package: (payload) => ipcRenderer.invoke('rnrc:package', payload),
    saveProject: (payload) => ipcRenderer.invoke('rnrc:saveProject', payload),
    openProject: () => ipcRenderer.invoke('rnrc:openProject'),
    setDirty: (v) => ipcRenderer.send('rnrc:setDirty', v),
    quitNow: () => ipcRenderer.send('rnrc:quitNow'),
    onSaveBeforeQuit: (cb) => ipcRenderer.on('rnrc:saveBeforeQuit', () => cb()),
    readAudioFile: (payload) => ipcRenderer.invoke('rnrc:readAudioFile', payload),
    pathExists: (payload) => ipcRenderer.invoke('rnrc:pathExists', payload),
    saveAudio: (payload) => ipcRenderer.invoke('rnrc:saveAudio', payload),
    pickAudioDir: () => ipcRenderer.invoke('rnrc:pickAudioDir'),
    gatherAudioFiles: (payload) => ipcRenderer.invoke('rnrc:gatherAudioFiles', payload)
});
