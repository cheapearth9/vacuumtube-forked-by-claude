//read-only fs backed by the files the bundler embedded (stylesheets, locales, the icon)
//only what the preload modules use is implemented

const path = require('path');
const files = require('vt-files');

function lookup(p, syscall) {
    const file = files[path.normalize(String(p))];
    if (!file) {
        const err = new Error(`ENOENT: no such file or directory, ${syscall} '${p}'`);
        err.code = 'ENOENT';
        throw err;
    }

    return file;
}

function read(p, options) {
    const file = lookup(p, 'open');
    const encoding = typeof options === 'string' ? options : options?.encoding;

    if (file.t !== undefined) { //text file
        if (encoding) return file.t;
        return { toString: (enc) => (enc === 'base64' ? btoa(unescape(encodeURIComponent(file.t))) : file.t) };
    }

    //binary file, stored as base64
    if (encoding) return atob(file.b);
    return { toString: (enc) => (enc === 'base64' ? file.b : atob(file.b)) };
}

function list(dir) {
    const normalized = path.normalize(String(dir));
    const names = [];

    for (const key of Object.keys(files)) {
        if (path.dirname(key) === normalized) names.push(path.basename(key));
    }

    if (names.length === 0) {
        const err = new Error(`ENOENT: no such file or directory, scandir '${dir}'`);
        err.code = 'ENOENT';
        throw err;
    }

    return names;
}

module.exports = {
    readFileSync: read,
    readdirSync: list,
    existsSync: (p) => !!files[path.normalize(String(p))],
    promises: {
        readFile: async (p, options) => read(p, options),
        readdir: async (dir) => list(dir)
    }
};
