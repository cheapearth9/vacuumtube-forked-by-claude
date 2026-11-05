//android only: fallbacks for css that old webviews (Chrome 52 on some tv boxes) don't know, used by VacuumTube's settings overlay
//only injected when the webview really lacks the feature, so modern ones keep the original styling

const css = require('../../src/preload/util/css')
const functions = require('../../src/preload/util/functions')

//flex "gap" came in chrome 84, so measure it instead of guessing from the version
function supportsFlexGap() {
    const flex = document.createElement('div')
    flex.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden'
    flex.appendChild(document.createElement('div'))
    flex.appendChild(document.createElement('div'))
    flex.firstChild.style.height = '1px'
    flex.lastChild.style.height = '1px'

    document.body.appendChild(flex)
    const supported = flex.scrollHeight === 3
    flex.remove()
    return supported
}

module.exports = async () => {
    await functions.waitForCondition(() => !!document.body)

    let text = ''

    if (!(window.CSS && CSS.supports && CSS.supports('inset', '0')) || !(CSS.supports && CSS.supports('width', 'min(1px, 2px)'))) {
        text += `
#vt-settings-overlay-root, .vt-settings-backdrop { top: 0; right: 0; bottom: 0; left: 0; }
.vt-settings-container { width: 90vw; max-width: 1100px; height: 86vh; max-height: 760px; }
`
    }

    if (!supportsFlexGap()) {
        text += `
.vt-settings-header > * + * { margin-left: 24px; }
.vt-tabbar > * + * { margin-left: 8px; }
.vt-view-header > * + * { margin-left: 16px; }
.vt-row > * + * { margin-left: 16px; }
.vt-row-info > * + * { margin-top: 3px; }
.vt-translate > * + * { margin-left: 6px; }
.vt-back > * + * { margin-left: 8px; }
.vt-section-grid { grid-gap: 24px 20px; }
`
    }

    if (text) css.inject('android-compat', text)
}
