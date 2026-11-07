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

    window.__vtVolumeStep = (delta) => {
        volume = Math.max(0, Math.min(100, volume + delta))
        configManager.set({ volume })
        apply()
        return volume;
    }
}
