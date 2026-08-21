#!/usr/bin/env node
'use strict';

// Standalone diagnostic: taps the same Redis channel as the main server's
// TeslaTelemetryRedisBridge, but prints every field in each payload instead
// of only the three media/volume keys TeslaVolumeDetector cares about. Use
// this to check whether your vehicle's Fleet Telemetry config is actually
// streaming Location/VehicleSpeed/DestinationName/MilesToArrival etc.
//
// Usage: node server/tesla-telemetry-inspect.js

require('dotenv').config();
const net = require('net');
const { RespParser } = require('./tesla-telemetry.js');

const redisUrl = process.env.TESLA_TELEMETRY_REDIS_URL || '';
const channelPattern = process.env.TESLA_TELEMETRY_REDIS_PATTERN || 'tesla_telemetry_V_*';

if (!redisUrl) {
  process.stderr.write('TESLA_TELEMETRY_REDIS_URL is not set.\n');
  process.exit(1);
}

const url = new URL(redisUrl);

function redisCommand(parts) {
  return `*${parts.length}\r\n${parts.map((part) => {
    const value = String(part);
    return `$${Buffer.byteLength(value)}\r\n${value}\r\n`;
  }).join('')}`;
}

function scalarOf(value) {
  if (!value || typeof value !== 'object') return value;
  if (value.invalid === true) return '(invalid)';
  for (const key of Object.keys(value)) {
    if (value[key] !== undefined && value[key] !== null) return `${key}=${JSON.stringify(value[key])}`;
  }
  return '(empty)';
}

const socket = net.createConnection({ host: url.hostname, port: Number(url.port || 6379) });
const parser = new RespParser((value) => {
  if (value instanceof Error) {
    process.stderr.write(`Redis error: ${value.message}\n`);
    return;
  }
  if (!Array.isArray(value) || value[0] !== 'pmessage' || value.length < 4) return;

  let payload;
  try {
    payload = JSON.parse(value[3]);
  } catch (_error) {
    process.stderr.write('Received non-JSON telemetry payload, skipping.\n');
    return;
  }

  const vin = typeof payload.vin === 'string' ? payload.vin : '(unknown vin)';
  const fields = Array.isArray(payload.data) ? payload.data : [];
  console.log(`\n[${payload.createdAt || new Date().toISOString()}] VIN ${vin} — ${fields.length} field(s)`);
  for (const datum of fields) {
    if (!datum || typeof datum.key !== 'string') continue;
    console.log(`  ${datum.key}: ${scalarOf(datum.value)}`);
  }
});

socket.setKeepAlive(true, 30_000);
socket.on('data', (chunk) => parser.push(chunk));
socket.on('connect', () => {
  const password = decodeURIComponent(url.password || '');
  const username = decodeURIComponent(url.username || '');
  if (password) socket.write(redisCommand(username ? ['AUTH', username, password] : ['AUTH', password]));
  socket.write(redisCommand(['PSUBSCRIBE', channelPattern]));
  console.log(`Subscribed to "${channelPattern}" on ${url.hostname}:${url.port || 6379}. Waiting for telemetry…`);
});
socket.on('error', (error) => process.stderr.write(`Connection error: ${error.message}\n`));
socket.on('close', () => console.log('Connection closed.'));

process.on('SIGINT', () => {
  socket.destroy();
  process.exit(0);
});
