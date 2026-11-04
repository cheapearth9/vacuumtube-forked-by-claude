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
