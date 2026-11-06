//runs the built bundle in headless chromium against a mock youtube.com/tv page and a fake AndroidBridge
//usage: node android/test/smoke.mjs   (needs playwright + a chromium, e.g. PLAYWRIGHT_BROWSERS_PATH)

import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')

const here = path.dirname(fileURLToPath(import.meta.url))
const assets = path.join(here, '../app/src/main/assets')
const bundle = fs.readFileSync(path.join(assets, 'vacuumtube.js'), 'utf-8')
const defaults = JSON.parse(fs.readFileSync(path.join(assets, 'config-defaults.json'), 'utf-8'))

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: [ '--no-sandbox' ] })
const page = await browser.newPage()

const logs = []
const errors = []
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => errors.push(String(e)))

await page.route('https://www.youtube.com/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/__vt/bundle.js') return route.fulfill({ contentType: 'text/javascript', body: bundle });

    route.fulfill({
        contentType: 'text/html',
        body: `<!DOCTYPE html><html><head><script src="/__vt/bundle.js"></script></head><body><div id="app">leanback</div></body></html>`
    })
})

//what the java bridge provides
await page.addInitScript((defaults) => {
    window.__bridgeCalls = []
    let config = { ...defaults, sponsorblock_uuid: 'test-uuid' }
    window.AndroidBridge = {
        getConfig: () => JSON.stringify(config),
        setConfig: (json) => { config = { ...config, ...JSON.parse(json) }; window.__bridgeCalls.push([ 'setConfig', json ]); return JSON.stringify(config) },
        getAppInfo: () => JSON.stringify({ version: '2.0.2-tv1', arch: 'arm64' }),
        getDeviceName: () => 'Test TV',
        requestMicrophone: () => true,
        reload: () => window.__bridgeCalls.push([ 'reload' ]),
        exit: () => window.__bridgeCalls.push([ 'exit' ]),
        openExternal: (url) => window.__bridgeCalls.push([ 'openExternal', url ])
    }
    window.ytcfg = { data_: { HL: 'en' } }
}, defaults)


//LEGACY=1 removes what chrome 52 doesn't have, so the polyfills have to carry the bundle
if (process.env.LEGACY) {
    await page.addInitScript(() => {
        const kill = (obj, names) => names.forEach((n) => { try { delete obj[n] } catch {} })
        kill(Object, [ 'entries', 'values', 'fromEntries', 'hasOwn' ])
        kill(String.prototype, [ 'padStart', 'padEnd', 'trimStart', 'trimEnd', 'replaceAll', 'at' ])
        kill(Array.prototype, [ 'at', 'flat', 'flatMap' ])
        kill(Promise.prototype, [ 'finally' ])
        kill(window, [ 'globalThis', 'queueMicrotask' ])
        for (const proto of [ Element.prototype, Document.prototype, DocumentFragment.prototype ]) kill(proto, [ 'append', 'prepend', 'replaceChildren' ])
        window.__legacyActive = typeof Object.entries === 'undefined' && typeof ''.padStart === 'undefined' && typeof [].flat === 'undefined'
    })
}
await page.goto('https://www.youtube.com/tv')
await page.waitForTimeout(1500)

const result = await page.evaluate(async () => {
    const out = {}
    out.loaded = window.__vtLoaded === true
    out.entriesNow = typeof Object.entries
    out.h5vcc = typeof window.h5vcc?.system?.getVideoContainerSizeOverride === 'function' ? window.h5vcc.system.getVideoContainerSizeOverride() : null
    out.styles = [ ...document.querySelectorAll('style[id^="vt-"]') ].map((s) => s.id)

    //ad blocking: JSON.parse is patched, so an innertube player response with ad placements should come back stripped
    const player = JSON.parse(JSON.stringify({ adPlacements: [ { x: 1 } ], adSlots: [ { y: 2 } ], videoDetails: { title: 'keep' } }))
    out.adPlacementsRemoved = Array.isArray(player.adPlacements) && player.adPlacements.length === 0
    out.adSlotsRemoved = Array.isArray(player.adSlots) && player.adSlots.length === 0
    out.videoKept = player.videoDetails?.title === 'keep'

    //back button maps to the cobalt back keycode
    let backKeyCode = null
    document.addEventListener('keydown', (e) => { backKeyCode = e.keyCode })
    window.__vtBack()
    out.backKeyCode = backKeyCode

    //exit goes through the bridge
    window.close()
    out.calls = window.__bridgeCalls.map((c) => c[0])

    JSON.parse('{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to continue"}}')
    out.diagnostic = [ ...document.querySelectorAll('div') ].map((d) => d.textContent).find((t) => t.startsWith('playability:')) || null
    const ok = new KeyboardEvent('keydown', { key: 'Unidentified' })
    Object.defineProperty(ok, 'keyCode', { value: 13 })
    out.okKey = ok.key //old android webviews send the remote's OK button as "Unidentified"
    window.dispatchEvent(new ErrorEvent('error', { message: 'x is not a function', filename: 'https://www.youtube.com/s/tv/base.js', lineno: 7 }))
    out.jsErrorShown = [ ...document.querySelectorAll('div') ].some((d) => d.textContent.includes('js error: x is not a function'))
    out.userAgentData = navigator.userAgentData?.platform
    return out
})

console.log(JSON.stringify(result, null, 2))
console.log('--- console (vt/errors only) ---')
for (const line of logs) if (/\[vt\]|error|warn/i.test(line)) console.log(line)
console.log('--- page errors ---')
for (const e of errors) console.log(e)

await browser.close()

const failed = !result.loaded || !result.adPlacementsRemoved || !result.adSlotsRemoved || !result.videoKept || result.backKeyCode !== 32769 || !result.diagnostic || result.okKey !== 'Enter' || !result.jsErrorShown || errors.length > 0
console.log(failed ? 'SMOKE FAILED' : 'SMOKE OK')
process.exit(failed ? 1 : 0)
