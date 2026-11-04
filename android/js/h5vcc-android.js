//android replacement for src/preload/modules/h5vcc
//same cobalt h5vcc object that leanback expects, minus the DIAL server (it needs raw sockets, which a webview doesn't have)

const configManager = require('../../src/preload/config')
const config = configManager.get()

function getMaxResolution() {
    let resolutions = [ '256x144', '426x240', '640x360', '854x480', '1280x720', '1920x1080', '2560x1440', '3840x2160', '7680x4320' ]
    let screenWidth = Math.max(window.screen.width, window.screen.height)
    let screenHeight = Math.min(window.screen.width, window.screen.height)

    for (let i = 0; i < resolutions.length; i++) {
        let [ width, height ] = resolutions[i].split('x').map(Number)
        if (screenWidth <= width && screenHeight <= height) return resolutions[i];
    }

    return `${window.screen.width}x${window.screen.height}`;
}

class DialServer { //accepts the registrations leanback makes and ignores them
    constructor(appName) {
        this.appName = appName;
    }

    onGet() {}
    onPost() {}
    onDelete() {}
}

module.exports = async () => {
    const maxResolution = getMaxResolution()

    window.h5vcc = {
        dial: { DialServer },
        runtime: {
            initialDeepLink: '',
            onDeepLink: { addListener: () => {} }
        },
        system: {
            getVideoContainerSizeOverride: () => { //unlock high res
                return config.unlock_resolution ? '7680x4320' : maxResolution;
            }
        }
    }
}
