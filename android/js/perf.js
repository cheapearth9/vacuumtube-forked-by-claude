//android only: "Reduce Animations", for slow tv boxes. Transitions are shortened (not removed: youtube waits for
//transitionend, so a duration of 0 can leave its interface stuck) and that is all. Blur, keyframe animations and shadows are left
//alone: removing the blur made the player's text unreadable on bright videos, and touching animations made the focus highlight flash white.

const css = require('../../src/preload/util/css')
const configManager = require('../../src/preload/config')

module.exports = () => {
    if (!configManager.get().reduce_animations) return;

    css.inject('reduce-animations', `
        *, *::before, *::after {
            transition-duration: 60ms !important;
            transition-delay: 0s !important;
            scroll-behavior: auto !important;
        }
    `)
}
