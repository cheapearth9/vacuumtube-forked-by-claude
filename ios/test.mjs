//loads the ios bundle (bridge.js + the shared scripts) into headless chromium with a mock page, the way the app does:
//window.__vtNative first, then the bundle inline in <head>. Checks the settings menu opens and that keys sent with __vtKey work.
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const here = path.dirname(fileURLToPath(import.meta.url))
const res = path.join(here, 'VacuumTube/Resources')
const bundle = fs.readFileSync(path.join(res, 'vacuumtube.js'), 'utf-8').replace(/<\/script/g, '<\\/script')
const defaults = JSON.parse(fs.readFileSync(path.join(res, 'config-defaults.json'), 'utf-8'))
const native = JSON.stringify({ config: JSON.stringify({ ...defaults, sponsorblock_uuid: 'x' }), app: { version: '2.0.2', arch: 'arm64', platform: 'iOS', device: 'iPhone' } })

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined, args: [ '--no-sandbox' ] })
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))

await page.route('https://www.youtube.com/**', (route) => {
    const html = `<!DOCTYPE html><html><head><script>window.__vtNative=${native};\n${bundle}</script><script>window.ytcfg = { data_: { HL: "en", INNERTUBE_CLIENT_NAME: "TVHTML5" } }</script></head><body style="background:#222"></body></html>`
    route.fulfill({ contentType: 'text/html', body: html })
})

await page.goto('https://www.youtube.com/tv')
await page.waitForFunction(() => typeof window.vtOpenSettingsOverlay === 'function', null, { timeout: 8000 })

const result = {}
result.bridge = await page.evaluate(() => typeof window.AndroidBridge.getConfig() === 'string' && !!JSON.parse(window.AndroidBridge.getConfig()).adblock !== undefined)
result.platform = await page.evaluate(async () => (await window.__vtElectron?.invoke?.('get-about-info'))?.platform ?? 'n/a')

//a key sent by the app reaches the page with the right keyCode
await page.evaluate(() => { window.__got = []; document.addEventListener('keydown', (e) => window.__got.push([ e.key, e.keyCode ])) })
await page.evaluate(() => { __vtKey('keydown', 'ArrowDown', 'ArrowDown', 40); __vtKey('keydown', 'a', 'KeyA', 65); __vtKey('keydown', 'Enter', 'Enter', 13) })
result.keys = await page.evaluate(() => window.__got)

await page.evaluate(() => window.vtOpenSettingsOverlay())
await page.waitForTimeout(500)
result.opened = await page.evaluate(() => !document.getElementById('vt-settings-overlay-root').classList.contains('vt-settings-hidden'))
await page.evaluate(() => __vtKey('keydown', 'Escape', 'Escape', 27)) //back
await page.waitForTimeout(400)
result.closedByEscape = await page.evaluate(() => document.getElementById('vt-settings-overlay-root').classList.contains('vt-settings-hidden'))

//settings saved from the page are posted to the app
result.saved = await page.evaluate(() => { window.AndroidBridge.setConfig(JSON.stringify({ volume: 50 })); return JSON.parse(window.AndroidBridge.getConfig()).volume })

console.log(JSON.stringify(result))
console.log('page errors:', errors.length ? errors : 'none')
await browser.close()
const ok = result.opened && result.closedByEscape && result.saved === 50 && JSON.stringify(result.keys) === JSON.stringify([ [ 'ArrowDown', 40 ], [ 'a', 65 ], [ 'Enter', 13 ] ]) && !errors.length
console.log(ok ? 'IOS JS OK' : 'IOS JS FAILED')
process.exit(ok ? 0 : 1)
