//ios only: stands in for the android java bridge (window.AndroidBridge) that the shared scripts talk to.
//the app puts its settings and info into window.__vtNative before the page runs; changes go back through the "vt" message handler.
//it also provides window.__vtKey, which the app uses to hand keyboard / remote presses to the page

(function () {
    var native = window.__vtNative || { config: '{}', app: {} }
    var config = {}

    try { config = JSON.parse(native.config) } catch (e) {}

    function post(message) {
        try { window.webkit.messageHandlers.vt.postMessage(message) } catch (e) {}
    }

    window.AndroidBridge = {
        getConfig: function () { return JSON.stringify(config) },
        setConfig: function (json) {
            try {
                var update = JSON.parse(json)
                for (var key in update) config[key] = update[key]
                post({ a: 'setConfig', config: JSON.stringify(config) })
            } catch (e) {}

            return JSON.stringify(config)
        },
        getAppInfo: function () { return JSON.stringify(native.app || {}) },
        checkForUpdates: function () {}, //updates come from the altstore source, not from the app
        hasWidevine: function () { return false }, //webkit has fairplay only, so youtube is told there is no drm at all
        getDeviceName: function () { return (native.app && native.app.device) || 'iPhone' },
        reload: function () { post({ a: 'reload' }) },
        exit: function () {}, //ios apps don't quit themselves
        requestMicrophone: function () { return true },
        openExternal: function (url) { post({ a: 'open', url: String(url) }) }
    }

    var codes = { ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37, ArrowRight: 39, Enter: 13, Escape: 27, Backspace: 8, Delete: 46, Tab: 9, ' ': 32 }

    //type: 'keydown' | 'keyup'. keyCode is passed explicitly: webkit ignores it in the event constructor
    window.__vtKey = function (type, key, code, keyCode, ctrl, shift, alt, meta) {
        var event = new KeyboardEvent(type, {
            key: key, code: code, bubbles: true, cancelable: true,
            ctrlKey: !!ctrl, shiftKey: !!shift, altKey: !!alt, metaKey: !!meta
        })

        var number = keyCode || codes[key] || (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0)
        Object.defineProperty(event, 'keyCode', { get: function () { return number } })
        Object.defineProperty(event, 'which', { get: function () { return number } })

        ;(document.activeElement || document.body || document).dispatchEvent(event)
    }
})();
