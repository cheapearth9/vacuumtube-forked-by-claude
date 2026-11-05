//the missing pieces of the browser, for old android webviews (a tv box can have Chrome 52 from 2016)
//syntax is handled at build time (esbuild lowers it), but library functions have to exist at run time

(function () {
    function define(target, name, value) {
        if (target[name] === undefined) {
            Object.defineProperty(target, name, { value: value, writable: true, configurable: true })
        }
    }

    define(window, 'globalThis', window)

    define(Object, 'entries', function (object) {
        return Object.keys(object).map(function (key) { return [ key, object[key] ] })
    })

    define(Object, 'values', function (object) {
        return Object.keys(object).map(function (key) { return object[key] })
    })

    define(Object, 'fromEntries', function (entries) {
        var result = {}
        Array.from(entries).forEach(function (entry) { result[entry[0]] = entry[1] })
        return result
    })

    define(Object, 'hasOwn', function (object, key) {
        return Object.prototype.hasOwnProperty.call(object, key)
    })

    function pad(string, length, fill, atStart) {
        string = String(string)
        fill = fill === undefined ? ' ' : String(fill)
        if (string.length >= length || fill === '') return string;

        var padding = ''
        while (padding.length < length - string.length) padding += fill
        padding = padding.slice(0, length - string.length)
        return atStart ? padding + string : string + padding;
    }

    define(String.prototype, 'padStart', function (length, fill) { return pad(this, length, fill, true) })
    define(String.prototype, 'padEnd', function (length, fill) { return pad(this, length, fill, false) })
    define(String.prototype, 'trimStart', function () { return String(this).replace(/^\s+/, '') })
    define(String.prototype, 'trimEnd', function () { return String(this).replace(/\s+$/, '') })

    define(String.prototype, 'replaceAll', function (search, replacement) {
        if (search instanceof RegExp) return String(this).replace(search, replacement);
        return String(this).split(String(search)).join(typeof replacement === 'function' ? replacement(String(search)) : replacement)
    })

    define(String.prototype, 'at', function (index) {
        var string = String(this)
        index = Math.trunc(index) || 0
        if (index < 0) index += string.length
        return index < 0 || index >= string.length ? undefined : string.charAt(index);
    })

    define(Array.prototype, 'at', function (index) {
        index = Math.trunc(index) || 0
        if (index < 0) index += this.length
        return this[index];
    })

    define(Array.prototype, 'flat', function (depth) {
        depth = depth === undefined ? 1 : depth
        return (function flatten(list, level) {
            return list.reduce(function (result, item) {
                return result.concat(Array.isArray(item) && level > 0 ? flatten(item, level - 1) : [ item ])
            }, [])
        })(this, depth)
    })

    define(Array.prototype, 'flatMap', function (callback, thisArg) {
        return Array.prototype.map.call(this, callback, thisArg).flat(1)
    })

    define(Promise.prototype, 'finally', function (callback) {
        var constructor = this.constructor
        return this.then(
            function (value) { return constructor.resolve(callback()).then(function () { return value }) },
            function (error) { return constructor.resolve(callback()).then(function () { throw error }) }
        )
    })

    define(window, 'queueMicrotask', function (callback) { Promise.resolve().then(callback) })

    //dom: append/prepend/replaceChildren came in chrome 54/86
    function nodesFrom(args) {
        var fragment = document.createDocumentFragment()
        Array.prototype.forEach.call(args, function (node) {
            fragment.appendChild(typeof node === 'string' ? document.createTextNode(node) : node)
        })
        return fragment;
    }

    ;[ Element.prototype, Document.prototype, DocumentFragment.prototype ].forEach(function (proto) {
        define(proto, 'append', function () { this.appendChild(nodesFrom(arguments)) })
        define(proto, 'prepend', function () { this.insertBefore(nodesFrom(arguments), this.firstChild) })
        define(proto, 'replaceChildren', function () {
            while (this.firstChild) this.removeChild(this.firstChild)
            this.appendChild(nodesFrom(arguments))
        })
    })
})()
