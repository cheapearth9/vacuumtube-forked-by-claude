//android only: the app's own player volume (the tv's volume keys still work on top of it). Always starts at 100%.
//upstream's volume-control module (key shortcuts and an on-screen bar) is skipped on android, this is just the setting.

const configManager = require('../../src/preload/config')

module.exports = () => {
    let volume = configManager.get().volume
    if (typeof volume !== 'number' || volume < 0 || volume > 100) volume = 100

    function apply() {
        for (const player of document.querySelectorAll('.html5-video-player')) {
            if (!player.setVolume) continue;
            if (player.getVolume && player.getVolume() === volume) continue;
            player.setVolume(volume)
        }
    }

    //youtube resets the volume for every new video, so keep applying it
    setInterval(apply, 1000)

    let toast = null, toastTimer = null

    //a small readout so the keys / buttons give feedback
    function showVolume() {
        if (!toast) {
            toast = document.createElement('div')
            toast.style.cssText = 'position:fixed;left:50%;bottom:8vh;transform:translateX(-50%);z-index:100000;padding:1vh 2vw;border-radius:2vh;background:rgba(0,0,0,0.75);color:#fff;font:600 3vh Roboto,Arial,sans-serif;pointer-events:none;opacity:0;transition:opacity 0.15s'
            document.documentElement.appendChild(toast)
        }
        toast.textContent = `Volume ${volume}%`
        toast.style.opacity = '1'
        clearTimeout(toastTimer)
        toastTimer = setTimeout(() => { toast.style.opacity = '0' }, 1200)
    }

    window.__vtVolumeStep = (delta) => {
        volume = Math.max(0, Math.min(100, volume + delta))
        configManager.set({ volume })
        apply()
        showVolume()
        return volume;
    }

    //Q = volume down, E = volume up (not while typing somewhere)
    window.addEventListener('keydown', (e) => {
        if (e.ctrlKey || e.metaKey || e.altKey) return;
        const key = (e.key || '').toLowerCase()
        if (key !== 'q' && key !== 'e') return;

        const active = document.activeElement
        if (active && (/^(input|textarea|select)$/i.test(active.tagName) || active.isContentEditable)) return;

        e.preventDefault()
        e.stopPropagation()
        window.__vtVolumeStep(key === 'e' ? 5 : -5)
    }, true)
}
