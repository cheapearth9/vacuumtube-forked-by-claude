//runs before any VacuumTube module. makes the webview behave like the cobalt-ish environment the desktop app gives leanback

const BACK = 32769; //cobalt's keycode for the back button (see modules/mouse.js)

function pressKey(keyCode) {
    for (const type of [ 'keydown', 'keyup' ]) {
        const event = new Event(type)
        event.keyCode = keyCode;
        document.dispatchEvent(event)
    }
}

//the app's back button can't reach the page as a key event, so the activity calls this instead
window.__vtBack = () => pressKey(BACK)

//youtube sends EXIT_APP and then window.close(), which a webview ignores for its main window
window.close = () => window.AndroidBridge.exit()

//desktop vacuumtube overrides client hints through the debugger, a webview has no equivalent, so mirror what src/ua.js reports
try {
    const brand = 'VacuumTube'
    const full = '27.lts.3'
    const data = {
        brands: [ { brand, version: '27' } ],
        mobile: false,
        platform: 'Starboard',
        getHighEntropyValues: async (hints) => ({
            brands: data.brands,
            mobile: false,
            platform: 'Starboard',
            platformVersion: '',
            architecture: 'arm',
            model: '',
            bitness: '64',
            uaFullVersion: full,
            fullVersionList: [ { brand, version: full } ]
        }),
        toJSON: () => ({ brands: data.brands, mobile: false, platform: 'Starboard' })
    }

    Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => data, configurable: true })
} catch (err) {
    console.warn('[preamble] could not override userAgentData', err)
}

//old android webviews report the remote's OK button (and some other keys) as key "Unidentified", while vacuumtube's settings
//overlay reads event.key, so give those events the key name that goes with their key code
try {
    const keyNames = { 13: 'Enter', 23: 'Enter', 27: 'Escape', 8: 'Backspace', 32: ' ', 37: 'ArrowLeft', 38: 'ArrowUp', 39: 'ArrowRight', 40: 'ArrowDown' }
    const original = Object.getOwnPropertyDescriptor(KeyboardEvent.prototype, 'key')

    if (original && original.get) {
        Object.defineProperty(KeyboardEvent.prototype, 'key', {
            configurable: true,
            get() {
                const key = original.get.call(this)
                if (key && key !== 'Unidentified') return key;

                return keyNames[this.keyCode] || key;
            }
        })
    }
} catch (err) {
    console.warn('[preamble] could not patch the key names', err)
}
