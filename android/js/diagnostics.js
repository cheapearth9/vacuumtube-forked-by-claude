//android only: when playback fails, show the real reason in the corner of the screen
//upstream's error popup blames "encryption" for every unknown player error, which isn't helpful when reporting a problem

const jsonMod = require('../../src/preload/util/jsonModifiers')

module.exports = () => {
    const lines = []
    let box = null
    let hideTimer = null

    function report(text) {
        try { if (!JSON.parse(window.AndroidBridge.getConfig()).debug_info) return } catch (e) { return } //only shown and logged when "Debug Info" is on
        console.warn('[vt-diagnostics] ' + text)

        text = String(text).slice(0, 700)
        if (lines.indexOf(text) !== -1) return; //drm failures repeat for every video, one line is enough
        lines.push(text)
        while (lines.length > 6) lines.shift()

        if (!box) {
            box = document.createElement('div')
            box.style.cssText = 'position:fixed;left:12px;bottom:12px;max-width:75vw;padding:6px 10px;border-radius:6px;' +
                'background:rgba(0,0,0,.7);color:#bbb;font:14px/1.3 sans-serif;z-index:2147483647;pointer-events:none;white-space:pre-wrap'
        }

        box.textContent = lines.join('\n')
        if (!box.isConnected) (document.body || document.documentElement).appendChild(box)

        clearTimeout(hideTimer)
        hideTimer = setTimeout(() => { box.remove(); lines.length = 0 }, 20000)
    }

    //the player's verdict about a video (not playable, login required, ...)
    jsonMod.addModifier((json) => {
        const status = json?.playabilityStatus
        if (status && status.status && status.status !== 'OK') {
            report('playability: ' + status.status + (status.reason ? ' - ' + status.reason : ''))
        }

        return json
    })

    //protected (DRM) playback asks for a key system, and failing to get one looks like a generic error
    if (navigator.requestMediaKeySystemAccess) {
        const original = navigator.requestMediaKeySystemAccess.bind(navigator)
        navigator.requestMediaKeySystemAccess = (keySystem, config) =>
            original(keySystem, config).catch((err) => {
                report('drm: ' + keySystem + ' - ' + (err && err.name))
                throw err
            })
    }

    window.__vtDiag = report

    //exceptions in youtube's own code are the usual reason a player stays blank on an old webview (a missing function, ...)
    window.addEventListener('error', (e) => {
        if (e.target && e.target !== window) return; //resource errors are handled elsewhere
        report('js error: ' + e.message + ' @' + String(e.filename || '').split('/').pop().slice(-40) + ':' + e.lineno)
    })

    window.addEventListener('unhandledrejection', (e) => {
        const reason = e.reason
        report('js rejection: ' + ((reason && (reason.stack || reason.message)) || reason))
    })

    const originalError = console.error
    console.error = function () {
        try {
            report('console: ' + Array.prototype.map.call(arguments, (a) => (a && a.message) || String(a)).join(' '))
        } catch (err) {}

        return originalError.apply(this, arguments)
    }

    //media source: this is where a codec or format the webview can't handle shows up
    if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
        const addSourceBuffer = MediaSource.prototype.addSourceBuffer
        MediaSource.prototype.addSourceBuffer = function (mime) {
            try {
                return addSourceBuffer.apply(this, arguments)
            } catch (err) {
                report('addSourceBuffer ' + mime + ' - ' + (err && err.name))
                throw err
            }
        }
    }

    //the video element's own error (decoder failures end up here)
    document.addEventListener('error', (e) => {
        const media = e.target
        if (media && media.tagName === 'VIDEO' && media.error) {
            report('video error ' + media.error.code + (media.error.message ? ': ' + media.error.message : ''))
        }
    }, true)
}
