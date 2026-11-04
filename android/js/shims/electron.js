//stand-in for electron's renderer-side api (ipcRenderer, shell) so VacuumTube's preload modules run unmodified inside an android webview
//everything that used to be an ipc round trip to the main process is answered here, or by the java side through window.AndroidBridge

const bridge = window.AndroidBridge;

const listeners = {};

function emit(channel, ...args) {
    for (const fn of (listeners[channel] || []).slice()) {
        try {
            fn({}, ...args);
        } catch (err) {
            console.error('[electron-shim] listener for', channel, 'failed', err);
        }
    }
}

window.__vtEmit = emit; //the java side calls this for events that come from the app (e.g. 'blur')

function parse(json, fallback) {
    try {
        return JSON.parse(json);
    } catch {
        return fallback;
    }
}

function appInfo() {
    const info = parse(bridge.getAppInfo(), {});
    return {
        status: 'unsupported',
        currentVersion: info.version || '',
        latestVersion: '',
        platform: 'Android',
        arch: info.arch || 'arm64',
        installationType: 'application',
        canAutoUpdate: false,
        progress: null
    };
}

const handlers = {
    'is-focused': () => document.hasFocus(),
    'is-steam': () => false,
    'reload': () => { bridge.reload(); },
    'relaunch-app': () => { bridge.reload(); },
    'set-fullscreen': () => {},
    'set-on-top': () => {},
    'get-deeplink': () => '',
    'get-userstyles': () => [],
    'open-userstyles-folder': () => {},
    'request-microphone-permission': () => bridge.requestMicrophone(),
    'get-about-info': () => appInfo(),
    'check-for-updates': () => appInfo(),
    'download-update': () => appInfo(),
    'install-update': () => {},
    'open-project-page': () => { bridge.openExternal('https://github.com/shy1132/VacuumTube'); },
    'open-release-page': () => { bridge.openExternal('https://github.com/shy1132/VacuumTube/releases'); },
    'open-update-download': () => { bridge.openExternal('https://github.com/shy1132/VacuumTube/releases'); }
};

const ipcRenderer = {
    sendSync(channel, ...args) {
        switch (channel) {
            case 'get-config':
                return parse(bridge.getConfig(), {});
            case 'set-config': {
                const updated = parse(bridge.setConfig(JSON.stringify(args[0] || {})), {});
                setTimeout(() => emit('config-update', updated), 0); //the main process used to broadcast this back
                return updated;
            }
            case 'can-auto-update':
                return false;
            default:
                console.warn('[electron-shim] unhandled sendSync', channel);
                return undefined;
        }
    },

    async invoke(channel, ...args) {
        const handler = handlers[channel];
        if (!handler) {
            console.warn('[electron-shim] unhandled invoke', channel);
            return undefined;
        }

        return handler(...args);
    },

    send(channel, ...args) {
        console.warn('[electron-shim] unhandled send', channel);
    },

    on(channel, fn) {
        (listeners[channel] = listeners[channel] || []).push(fn);
        return ipcRenderer;
    },

    once(channel, fn) {
        const wrapper = (...args) => {
            ipcRenderer.removeListener(channel, wrapper);
            fn(...args);
        };

        return ipcRenderer.on(channel, wrapper);
    },

    removeListener(channel, fn) {
        listeners[channel] = (listeners[channel] || []).filter((l) => l !== fn);
        return ipcRenderer;
    }
};

ipcRenderer.addListener = ipcRenderer.on;
ipcRenderer.off = ipcRenderer.removeListener;

const shell = {
    openExternal(url) {
        bridge.openExternal(String(url));
        return Promise.resolve();
    }
};

module.exports = { ipcRenderer, shell };
