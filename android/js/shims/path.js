//posix-only path, just enough for the preload modules (they only build paths to bundled files)

function normalize(p) {
    const absolute = p.startsWith('/');
    const out = [];

    for (const segment of p.split('/')) {
        if (!segment || segment === '.') continue;
        if (segment === '..') out.pop();
        else out.push(segment);
    }

    return (absolute ? '/' : '') + out.join('/');
}

function join(...parts) {
    return normalize(parts.filter((p) => p !== '').join('/'));
}

function resolve(...parts) {
    let result = '';
    for (let i = parts.length - 1; i >= 0 && !result.startsWith('/'); i--) {
        result = parts[i] + (result ? '/' + result : '');
    }

    return normalize(result.startsWith('/') ? result : '/vt/' + result);
}

function dirname(p) {
    const index = p.lastIndexOf('/');
    if (index === -1) return '.';
    if (index === 0) return '/';
    return p.slice(0, index);
}

function basename(p, ext) {
    let base = p.slice(p.lastIndexOf('/') + 1);
    if (ext && base.endsWith(ext)) base = base.slice(0, -ext.length);
    return base;
}

function extname(p) {
    const base = basename(p);
    const index = base.lastIndexOf('.');
    return index <= 0 ? '' : base.slice(index);
}

module.exports = { sep: '/', normalize, join, resolve, dirname, basename, extname };
