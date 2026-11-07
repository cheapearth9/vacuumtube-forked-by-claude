//builds the script that gets injected into youtube.com/tv inside the android webview (no dependencies, plain node)
//
//the upstream preload modules are commonjs written for electron. rather than rewriting them, this bundles them as they are:
//  - require('electron' | 'fs' | 'path' | 'os' | 'events') are answered by android/js/shims
//  - fs reads (stylesheets, locales, the icon) are served from files embedded in the bundle
//  - modules/h5vcc is swapped for android/js/h5vcc-android.js
//also writes assets/config-defaults.json, taken straight from the defaults in src/config.js so they never drift

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const assets = path.join(here, 'app/src/main/assets')
const VIRTUAL_ROOT = '/vt'

const SKIP_MODULES = new Set([ 'no-f11', 'volume-control' ]) //desktop only; a tv's volume is the tv's, so the app always plays at full volume
const REPLACE_MODULES = { h5vcc: 'android/js/h5vcc-android.js' }
const EXTRA_MODULES = { 'android-diagnostics': 'android/js/diagnostics.js', 'android-compat': 'android/js/compat.js', 'android-volume': 'android/js/volume.js' } //android only additions

const ALIASES = {
    electron: 'android/js/shims/electron.js',
    fs: 'android/js/shims/fs.js',
    path: 'android/js/shims/path.js',
    os: 'android/js/shims/os.js',
    events: 'android/js/shims/events.js',
    crypto: 'android/js/shims/empty.js',
    net: 'android/js/shims/empty.js',
    http: 'android/js/shims/empty.js',
    dgram: 'android/js/shims/empty.js',
    'vt-files': '<virtual:files>'
}

const posix = (p) => p.split(path.sep).join('/')
const rel = (abs) => posix(path.relative(root, abs))

//embedded files ---------------------------------------------------------------------------------

function embeddedFiles() {
    const files = {}

    const addText = (relPath) => {
        files[`${VIRTUAL_ROOT}/${relPath}`] = { t: fs.readFileSync(path.join(root, relPath), 'utf-8') }
    }

    const walk = (dir) => {
        for (const entry of fs.readdirSync(path.join(root, dir), { withFileTypes: true })) {
            const entryPath = `${dir}/${entry.name}`
            if (entry.isDirectory()) walk(entryPath)
            else if (entry.name.endsWith('.css')) addText(entryPath)
        }
    }

    walk('src/preload')

    for (const name of fs.readdirSync(path.join(root, 'locale'))) {
        if (name.endsWith('.json')) addText(`locale/${name}`)
    }

    files[`${VIRTUAL_ROOT}/assets/icon.png`] = { b: fs.readFileSync(path.join(root, 'assets/icon.png')).toString('base64') }

    return files
}

//entry ------------------------------------------------------------------------------------------

function entrySource() {
    const modulesDir = path.join(root, 'src/preload/modules')
    const loaders = []

    for (const entry of fs.readdirSync(modulesDir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
        const name = entry.name.replace(/\.js$/, '')
        if (SKIP_MODULES.has(name)) continue

        let file;
        if (REPLACE_MODULES[name]) file = REPLACE_MODULES[name]
        else if (entry.isDirectory()) file = `src/preload/modules/${entry.name}/index.js`
        else if (entry.name.endsWith('.js')) file = `src/preload/modules/${entry.name}`
        else continue;

        loaders.push(`[ ${JSON.stringify(name)}, () => require(${JSON.stringify('../../' + file)}) ]`)
    }

    for (const [ name, file ] of Object.entries(EXTRA_MODULES)) {
        loaders.push(`[ ${JSON.stringify(name)}, () => require(${JSON.stringify('../../' + file)}) ]`)
    }

    return `
require('./polyfills.js')
require('./preamble.js')

if (location.host === 'www.youtube.com' && location.pathname === '/tv' && !window.__vtLoaded) {
    window.__vtLoaded = true

    const earlyYtcfg = window.ytcfg //only set when injected late: configOverrides redefines window.ytcfg, which would lose it

    const xhrModifiers = require('../../src/preload/util/xhrModifiers.js')
    xhrModifiers.block() //same as the desktop app: leanback fires an xhr so early that modules would miss it

    const loaders = [
        ${loaders.join(',\n        ')}
    ]

    const loaded = []
    for (const [ name, load ] of loaders) {
        try {
            loaded.push([ name, load() ])
        } catch (err) {
            console.error('[vt] module failed to load:', name, err)
        }
    }

    if (earlyYtcfg !== undefined && window.ytcfg === undefined) window.ytcfg = earlyYtcfg

    for (const [ name, mod ] of loaded) {
        try {
            if (typeof mod === 'function') {
                const result = mod()
                if (result && typeof result.catch === 'function') result.catch((err) => console.error('[vt] module failed:', name, err))
            }
        } catch (err) {
            console.error('[vt] module failed:', name, err)
        }
    }

    xhrModifiers.unblock()
    console.log('[vt] loaded ' + loaded.length + ' modules')
}
`
}

//bundler ----------------------------------------------------------------------------------------

const modules = new Map() //id -> { source, deps }

function resolveRelative(fromId, spec) {
    const base = path.posix.join(path.posix.dirname(fromId), spec)
    for (const candidate of [ base, base + '.js', base + '.json', base + '/index.js' ]) {
        const abs = path.join(root, candidate)
        if (fs.existsSync(abs) && fs.statSync(abs).isFile()) return candidate;
    }

    throw new Error(`cannot resolve ${spec} from ${fromId}`)
}

function addModule(id, source) {
    if (modules.has(id)) return;

    const entry = { source, deps: {} }
    modules.set(id, entry) //registered first so circular requires don't recurse forever

    for (const match of source.matchAll(/\brequire\(\s*(['"])([^'"]+)\1\s*\)/g)) {
        const spec = match[2]
        let depId;

        if (spec.startsWith('.')) {
            depId = resolveRelative(id, spec)
        } else if (ALIASES[spec]) {
            depId = ALIASES[spec]
        } else {
            throw new Error(`${id} requires '${spec}', which has no android shim (add one to ALIASES in build-js.mjs)`)
        }

        entry.deps[spec] = depId;

        if (depId === '<virtual:files>') continue;
        if (!modules.has(depId)) {
            const abs = path.join(root, depId)
            const text = fs.readFileSync(abs, 'utf-8')
            addModule(depId, depId.endsWith('.json') ? `module.exports = ${text}` : text)
        }
    }
}

function build() {
    const entryId = 'android/js/<entry>.js'
    addModule(entryId, entrySource())

    const files = embeddedFiles()
    const parts = []

    for (const [ id, { source, deps } ] of modules) {
        const dir = `${VIRTUAL_ROOT}/${path.posix.dirname(id)}`
        parts.push(
            `${JSON.stringify(id)}: [ function (module, exports, require, __dirname, __filename, process, global) {\n${source}\n}, ${JSON.stringify(deps)}, ${JSON.stringify(dir)} ]`
        )
    }

    return `(function () {
var __files = ${JSON.stringify(files)};
var __process = { platform: 'android', arch: 'arm64', env: {}, versions: {}, argv: [], cwd: function () { return '${VIRTUAL_ROOT}'; }, nextTick: function (fn) { var a = [].slice.call(arguments, 1); setTimeout(function () { fn.apply(null, a); }, 0); } };
var __defs = {
${parts.join(',\n')}
};
var __cache = {};
function __require(id) {
    if (id === '<virtual:files>') return __files;
    if (__cache[id]) return __cache[id].exports;

    var def = __defs[id];
    var module = __cache[id] = { exports: {} };
    var deps = def[1];
    var local = function (spec) {
        if (!(spec in deps)) throw new Error('unknown require ' + spec + ' in ' + id);
        return __require(deps[spec]);
    };

    def[0].call(module.exports, module, module.exports, local, def[2], def[2] + '/' + id.split('/').pop(), __process, window);
    return module.exports;
}
__require(${JSON.stringify(entryId)});
})();
`
}

//config defaults --------------------------------------------------------------------------------

function configDefaults() {
    const source = fs.readFileSync(path.join(root, 'src/config.js'), 'utf-8')
    const match = source.match(/const defaults = (\{[\s\S]*?\n\})\n/)
    if (!match) throw new Error('could not find the defaults object in src/config.js')

    const defaults = new Function('crypto', `return (${match[1]})`)({ randomUUID: () => '__GENERATE__' })

    return {
        ...defaults,
        //the desktop-only things that make no sense in the android app
        device_discoverability: false, //DIAL casting needs raw sockets
        auto_update: false,
        fullscreen: true,
        keep_on_top: false,
        no_window_decorations: false
    }
}

fs.mkdirSync(assets, { recursive: true })

const bundle = build()
fs.writeFileSync(path.join(assets, 'vacuumtube.js'), bundle)
fs.writeFileSync(path.join(assets, 'config-defaults.json'), JSON.stringify(configDefaults(), null, 2))

//old webviews (a tv box can ship Chrome 52 from 2016) can't parse optional chaining, async/await, class fields and the like
//if ESBUILD is set (e.g. "npx --yes esbuild@0.28.2", as in CI) the bundle is rewritten in place for those
const bundlePath = path.join(assets, 'vacuumtube.js')
if (process.env.ESBUILD) {
    const { execSync } = await import('node:child_process')
    execSync(`${process.env.ESBUILD} "${bundlePath}" --target=chrome52 --outfile="${bundlePath}" --allow-overwrite --log-level=warning`, { stdio: 'inherit' })
    console.log('lowered the bundle for chrome 52')
}

console.log(`bundled ${modules.size} modules, ${(bundle.length / 1024).toFixed(0)} KiB -> ${rel(path.join(assets, 'vacuumtube.js'))}`)
