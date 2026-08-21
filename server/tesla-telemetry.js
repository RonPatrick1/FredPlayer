'use strict';

const EventEmitter = require('events');
const net = require('net');

function numericValue(value) {
  if (!value || typeof value !== 'object' || value.invalid === true) return null;
  for (const key of ['doubleValue', 'floatValue', 'intValue', 'longValue', 'stringValue']) {
    if (value[key] === undefined || value[key] === null || value[key] === '') continue;
    const parsed = Number(value[key]);
    if (Number.isFinite(parsed)) return parsed;
  }
  return null;
}

function scalarValue(value) {
  if (!value || typeof value !== 'object' || value.invalid === true) return null;
  for (const key of [
    'stringValue', 'doubleValue', 'floatValue', 'intValue', 'longValue',
    'booleanValue', 'shiftStateValue', 'mediaStatusValue',
  ]) {
    if (value[key] !== undefined && value[key] !== null) return value[key];
  }
  return null;
}

function telemetryFields(payload) {
  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.data)) return new Map();
  return new Map(payload.data
    .filter((datum) => datum && typeof datum.key === 'string')
    .map((datum) => [datum.key, datum.value]));
}

class TeslaVolumeDetector extends EventEmitter {
  constructor({ gestureDelayMs = 1600 } = {}) {
    super();
    this.gestureDelayMs = gestureDelayMs;
    this.volume = null;
    this.increment = null;
    this.playbackStatus = null;
    this.pendingGesture = null;
    this.gestureTimer = null;
    this.suppressedVolume = null;
  }

  ingest(payload) {
    const fields = telemetryFields(payload);
    const timestamp = typeof payload?.createdAt === 'string'
      ? payload.createdAt : new Date().toISOString();
    const increment = numericValue(fields.get('MediaAudioVolumeIncrement'));
    if (increment !== null && increment > 0 && increment <= 11) this.increment = increment;

    const events = [];
    const playbackStatus = scalarValue(fields.get('MediaPlaybackStatus'));
    if (playbackStatus !== null && playbackStatus !== this.playbackStatus) {
      this.playbackStatus = playbackStatus;
      events.push({
        type: 'media-playback-status',
        source: 'fleet-telemetry',
        status: String(playbackStatus),
        observedAt: timestamp,
      });
    }

    const volume = numericValue(fields.get('MediaAudioVolume'));
    if (volume === null || volume < 0 || volume > 11) {
      for (const event of events) this.emit('event', event);
      return events;
    }

    const previousVolume = this.volume;
    this.volume = volume;
    if (previousVolume === null || volume === previousVolume) {
      for (const event of events) this.emit('event', event);
      return events;
    }

    if (this.suppressedVolume) {
      const tolerance = Math.max(0.02, (this.increment || Math.abs(volume - previousVolume)) * 0.35);
      if (Date.now() > this.suppressedVolume.expiresAt) {
        this.suppressedVolume = null;
      } else if (Math.abs(volume - this.suppressedVolume.targetVolume) <= tolerance) {
        events.push({
          type: 'volume-compensation-observed',
          source: 'fleet-telemetry-volume-inference',
          volume,
          previousVolume,
          observedAt: timestamp,
        });
        this.suppressedVolume = null;
        for (const event of events) this.emit('event', event);
        return events;
      }
    }

    const delta = volume - previousVolume;
    const step = this.increment && this.increment > 0 ? this.increment : Math.abs(delta);
    const rawCount = Math.abs(delta) / step;
    const count = Math.round(rawCount);
    const tolerance = Math.max(0.02, step * 0.35);
    if (count >= 1 && count <= 20 && Math.abs(Math.abs(delta) - count * step) <= tolerance) {
      const event = {
        type: 'volume-detent-candidate',
        source: 'fleet-telemetry-volume-inference',
        direction: delta > 0 ? 'up' : 'down',
        count,
        volume,
        previousVolume,
        increment: step,
        observedAt: timestamp,
      };
      events.push(event);
      this.#accumulateGesture(event);
    }

    for (const event of events) this.emit('event', event);
    return events;
  }

  #accumulateGesture(event) {
    if (this.pendingGesture && this.pendingGesture.direction !== event.direction) {
      this.flushGesture();
    }
    if (!this.pendingGesture) {
      this.pendingGesture = {
        type: 'volume-gesture-candidate',
        source: 'fleet-telemetry-volume-inference',
        direction: event.direction,
        count: 0,
        previousVolume: event.previousVolume,
        volume: event.volume,
        increment: event.increment,
        firstObservedAt: event.observedAt,
        observedAt: event.observedAt,
      };
    }
    this.pendingGesture.count += event.count;
    this.pendingGesture.volume = event.volume;
    this.pendingGesture.increment = event.increment;
    this.pendingGesture.observedAt = event.observedAt;
    if (this.gestureTimer) clearTimeout(this.gestureTimer);
    this.gestureTimer = setTimeout(() => this.flushGesture(), this.gestureDelayMs);
    this.gestureTimer.unref?.();
  }

  flushGesture() {
    if (this.gestureTimer) clearTimeout(this.gestureTimer);
    this.gestureTimer = null;
    const gesture = this.pendingGesture;
    this.pendingGesture = null;
    if (gesture) this.emit('event', gesture);
    return gesture;
  }

  suppressNextVolume(targetVolume, { ttlMs = 10_000 } = {}) {
    const numericTarget = Number(targetVolume);
    if (!Number.isFinite(numericTarget) || numericTarget < 0 || numericTarget > 11) return false;
    this.suppressedVolume = {
      targetVolume: numericTarget,
      expiresAt: Date.now() + Math.max(1000, Number(ttlMs) || 10_000),
    };
    return true;
  }

  close() {
    if (this.gestureTimer) clearTimeout(this.gestureTimer);
    this.gestureTimer = null;
    this.pendingGesture = null;
    this.suppressedVolume = null;
  }
}

class RespParser {
  constructor(onValue) {
    this.onValue = onValue;
    this.buffer = Buffer.alloc(0);
  }

  push(chunk) {
    this.buffer = Buffer.concat([this.buffer, Buffer.from(chunk)]);
    let offset = 0;
    while (offset < this.buffer.length) {
      const parsed = this.#parseAt(offset);
      if (!parsed) break;
      offset = parsed.offset;
      this.onValue(parsed.value);
    }
    if (offset) this.buffer = this.buffer.subarray(offset);
  }

  #line(offset) {
    const end = this.buffer.indexOf('\r\n', offset);
    if (end < 0) return null;
    return { text: this.buffer.toString('utf8', offset, end), offset: end + 2 };
  }

  #parseAt(offset) {
    if (offset >= this.buffer.length) return null;
    const prefix = String.fromCharCode(this.buffer[offset]);
    const line = this.#line(offset + 1);
    if (!line) return null;
    if (prefix === '+' || prefix === ':') {
      return { value: prefix === ':' ? Number(line.text) : line.text, offset: line.offset };
    }
    if (prefix === '-') return { value: new Error(line.text), offset: line.offset };
    if (prefix === '$') {
      const length = Number(line.text);
      if (length === -1) return { value: null, offset: line.offset };
      if (!Number.isInteger(length) || length < 0 || this.buffer.length < line.offset + length + 2) {
        return null;
      }
      return {
        value: this.buffer.toString('utf8', line.offset, line.offset + length),
        offset: line.offset + length + 2,
      };
    }
    if (prefix === '*') {
      const count = Number(line.text);
      if (count === -1) return { value: null, offset: line.offset };
      if (!Number.isInteger(count) || count < 0) return null;
      const values = [];
      let itemOffset = line.offset;
      for (let i = 0; i < count; i += 1) {
        const item = this.#parseAt(itemOffset);
        if (!item) return null;
        values.push(item.value);
        itemOffset = item.offset;
      }
      return { value: values, offset: itemOffset };
    }
    return { value: new Error(`Unsupported Redis response prefix ${prefix}`), offset: line.offset };
  }
}

function redisCommand(parts) {
  return `*${parts.length}\r\n${parts.map((part) => {
    const value = String(part);
    return `$${Buffer.byteLength(value)}\r\n${value}\r\n`;
  }).join('')}`;
}

class TeslaTelemetryRedisBridge extends EventEmitter {
  constructor({ redisUrl, channelPattern = 'tesla_telemetry_V_*', reconnectMs = 2000 } = {}) {
    super();
    this.url = new URL(redisUrl);
    if (this.url.protocol !== 'redis:') throw new Error('Tesla telemetry Redis URL must use redis://');
    this.channelPattern = channelPattern;
    this.reconnectMs = reconnectMs;
    this.detectors = new Map();
    this.socket = null;
    this.reconnectTimer = null;
    this.closed = false;
  }

  start() {
    this.closed = false;
    this.#connect();
  }

  #connect() {
    if (this.closed || this.socket) return;
    const parser = new RespParser((value) => this.#redisValue(value));
    const socket = net.createConnection({
      host: this.url.hostname,
      port: Number(this.url.port || 6379),
    });
    this.socket = socket;
    socket.setKeepAlive(true, 30_000);
    socket.on('data', (chunk) => parser.push(chunk));
    socket.on('connect', () => {
      const password = decodeURIComponent(this.url.password || '');
      const username = decodeURIComponent(this.url.username || '');
      if (password) socket.write(redisCommand(username ? ['AUTH', username, password] : ['AUTH', password]));
      socket.write(redisCommand(['PSUBSCRIBE', this.channelPattern]));
      this.emit('status', { connected: true });
    });
    socket.on('error', (error) => this.emit('status', { connected: false, error: error.message }));
    socket.on('close', () => {
      if (this.socket === socket) this.socket = null;
      this.emit('status', { connected: false });
      if (!this.closed && !this.reconnectTimer) {
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null;
          this.#connect();
        }, this.reconnectMs);
        this.reconnectTimer.unref?.();
      }
    });
  }

  #redisValue(value) {
    if (value instanceof Error) {
      this.emit('status', { connected: false, error: value.message });
      return;
    }
    if (!Array.isArray(value) || value[0] !== 'pmessage' || value.length < 4) return;
    let payload;
    try {
      payload = JSON.parse(value[3]);
    } catch (_error) {
      this.emit('status', { connected: true, error: 'invalid telemetry JSON' });
      return;
    }
    const vin = typeof payload.vin === 'string' ? payload.vin : String(value[2]).match(/\{([^}]+)\}$/)?.[1];
    if (!vin) return;
    let detector = this.detectors.get(vin);
    if (!detector) {
      detector = new TeslaVolumeDetector();
      detector.on('event', (event) => this.emit('event', event));
      this.detectors.set(vin, detector);
    }
    detector.ingest(payload);
  }

  suppressNextVolume(targetVolume, options) {
    let suppressed = false;
    for (const detector of this.detectors.values()) {
      suppressed = detector.suppressNextVolume(targetVolume, options) || suppressed;
    }
    return suppressed;
  }

  close() {
    this.closed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.destroy();
    this.socket = null;
    for (const detector of this.detectors.values()) detector.close();
    this.detectors.clear();
  }
}

module.exports = {
  RespParser,
  TeslaTelemetryRedisBridge,
  TeslaVolumeDetector,
  numericValue,
  telemetryFields,
};
