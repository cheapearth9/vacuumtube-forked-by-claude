//checks that the VacuumTube entry shows up in youtube's settings response and that its overlay opens (headless chromium, mock page)
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
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))

await page.route('https://www.youtube.com/**', (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/__vt/bundle.js') return route.fulfill({ contentType: 'text/javascript', body: bundle })
    route.fulfill({ contentType: 'text/html', body: '<!DOCTYPE html><html><head><script src="/__vt/bundle.js"></script><script>window.ytcfg = { data_: { HL: \"en\", INNERTUBE_CLIENT_NAME: \"TVHTML5\" } }</script></head><body style="background:#222"></body></html>' })
})

await page.addInitScript((defaults) => {
    let config = { ...defaults, sponsorblock_uuid: 'x' }
    window.AndroidBridge = {
        getConfig: () => JSON.stringify(config),
        setConfig: (j) => { config = { ...config, ...JSON.parse(j) }; return JSON.stringify(config) },
        getAppInfo: () => JSON.stringify({ version: '2.0.2-tv1', arch: 'arm64' }),
        getDeviceName: () => 'TV', requestMicrophone: () => true, reload() {}, exit() {}, openExternal() {}
    }
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
await page.waitForFunction(() => typeof window.vtOpenSettingsOverlay === 'function', null, { timeout: 8000 })

const result = await page.evaluate(() => {
    //shape of youtube's get_settings response: a list of categories
    const response = JSON.parse(JSON.stringify({ items: [ { settingCategoryCollectionRenderer: { categoryId: 'SETTINGS_CAT_ALL', items: [] } } ] }))
    const first = response.items[0]?.settingCategoryCollectionRenderer
    const out = {
        entryAdded: first?.categoryId === 'SETTINGS_CAT_VACUUMTUBE_OVERLAY',
        entryTitle: first?.title?.runs?.[0]?.text,
        buttonTitle: first?.items?.[0]?.settingActionRenderer?.title?.runs?.[0]?.text
    }
    window.vtOpenSettingsOverlay()
    return out
})

await page.waitForTimeout(600)
result.overlayVisible = await page.evaluate(() => { const o = document.querySelector('[class*="vt-"]'); return !!o && o.getBoundingClientRect().width > 0 })
result.text = await page.evaluate(() => document.body.innerText.slice(0, 300).replace(/\s+/g, ' '))
await page.screenshot({ path: process.env.SHOT || '/tmp/settings.png' })

console.log(JSON.stringify(result, null, 2))
console.log('page errors:', errors.length ? errors : 'none')
await browser.close()
process.exit(result.entryAdded && result.overlayVisible && !errors.length ? 0 : 1)
