module.exports = {
    hostname: () => (window.AndroidBridge ? window.AndroidBridge.getDeviceName() : 'Android TV'),
    platform: () => 'linux',
    arch: () => 'arm64',
    homedir: () => '/vt',
    tmpdir: () => '/tmp',
    networkInterfaces: () => ({})
};
