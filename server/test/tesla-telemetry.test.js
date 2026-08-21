'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
  RespParser,
  TeslaVolumeDetector,
  numericValue,
} = require('../tesla-telemetry.js');

function payload(data, createdAt = '2026-08-16T12:00:00Z') {
  return { vin: 'TESTVIN0000000001', createdAt, data };
}

function datum(key, value) {
  return { key, value };
}

test('numericValue accepts decoded Tesla scalar representations', () => {
  assert.equal(numericValue({ doubleValue: 4.5 }), 4.5);
  assert.equal(numericValue({ stringValue: '0.5' }), 0.5);
  assert.equal(numericValue({ invalid: true, doubleValue: 3 }), null);
});

test('volume detector establishes a baseline without inventing a click', () => {
  const detector = new TeslaVolumeDetector();
  const events = detector.ingest(payload([
    datum('MediaAudioVolumeIncrement', { doubleValue: 0.5 }),
    datum('MediaAudioVolume', { doubleValue: 4 }),
  ]));
  assert.deepEqual(events, []);
  detector.close();
});

test('volume detector infers one and two detents from volume deltas', () => {
  const detector = new TeslaVolumeDetector();
  const emitted = [];
  detector.on('event', (event) => emitted.push(event));
  detector.ingest(payload([
    datum('MediaAudioVolumeIncrement', { doubleValue: 0.5 }),
    datum('MediaAudioVolume', { doubleValue: 4 }),
  ]));
  detector.ingest(payload([datum('MediaAudioVolume', { doubleValue: 4.5 })]));
  detector.ingest(payload([datum('MediaAudioVolume', { doubleValue: 5.5 })]));
  assert.deepEqual(emitted.map(({ type, direction, count }) => ({ type, direction, count })), [
    { type: 'volume-detent-candidate', direction: 'up', count: 1 },
    { type: 'volume-detent-candidate', direction: 'up', count: 2 },
  ]);
  assert.deepEqual(detector.flushGesture(), {
    type: 'volume-gesture-candidate',
    source: 'fleet-telemetry-volume-inference',
    direction: 'up',
    count: 3,
    previousVolume: 4,
    volume: 5.5,
    increment: 0.5,
    firstObservedAt: '2026-08-16T12:00:00Z',
    observedAt: '2026-08-16T12:00:00Z',
  });
  detector.close();
});

test('volume detector reports playback state changes but not duplicates', () => {
  const detector = new TeslaVolumeDetector();
  const first = detector.ingest(payload([
    datum('MediaPlaybackStatus', { stringValue: 'Playing' }),
  ]));
  const duplicate = detector.ingest(payload([
    datum('MediaPlaybackStatus', { stringValue: 'Playing' }),
  ]));
  assert.equal(first[0].type, 'media-playback-status');
  assert.equal(first[0].status, 'Playing');
  assert.deepEqual(duplicate, []);
  detector.close();
});

test('RESP parser handles fragmented Redis pubsub arrays', () => {
  const values = [];
  const parser = new RespParser((value) => values.push(value));
  const message = '*4\r\n$8\r\npmessage\r\n$3\r\nfoo\r\n$5\r\nfoo:1\r\n$7\r\n{\"x\":1}\r\n';
  parser.push(message.slice(0, 17));
  assert.deepEqual(values, []);
  parser.push(message.slice(17));
  assert.deepEqual(values, [['pmessage', 'foo', 'foo:1', '{"x":1}']]);
});
