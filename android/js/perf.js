//android only: "Reduce Animations", for slow tv boxes. Transitions are made almost instant (not removed: youtube waits for
//transitionend / animationend, so a duration of exactly 0 can leave its interface stuck), and costly effects are dropped.

const css = require('../../src/preload/util/css')
const configManager = require('../../src/preload/config')

module.exports = () => {
    if (!configManager.get().reduce_animations) return;

    css.inject('reduce-animations', `
        *, *::before, *::after {
            transition-duration: 1ms !important;
            transition-delay: 0s !important;
            animation-duration: 1ms !important;
            animation-delay: 0s !important;
            scroll-behavior: auto !important;
            backdrop-filter: none !important;
            -webkit-backdrop-filter: none !important;
            box-shadow: none !important;
            text-shadow: none !important;
        }
    `)
}
