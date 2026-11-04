class EventEmitter {
    constructor() {
        this._events = {};
    }

    on(name, fn) {
        (this._events[name] = this._events[name] || []).push(fn);
        return this;
    }

    once(name, fn) {
        const wrapper = (...args) => {
            this.off(name, wrapper);
            fn.apply(this, args);
        };

        return this.on(name, wrapper);
    }

    off(name, fn) {
        this._events[name] = (this._events[name] || []).filter((l) => l !== fn);
        return this;
    }

    emit(name, ...args) {
        const list = (this._events[name] || []).slice();
        for (const fn of list) fn.apply(this, args);
        return list.length > 0;
    }

    removeAllListeners(name) {
        if (name) delete this._events[name];
        else this._events = {};
        return this;
    }

    listenerCount(name) {
        return (this._events[name] || []).length;
    }
}

EventEmitter.prototype.addListener = EventEmitter.prototype.on;
EventEmitter.prototype.removeListener = EventEmitter.prototype.off;

module.exports = { EventEmitter };
