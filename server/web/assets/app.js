const $ = (selector) => document.querySelector(selector);
const loginView = $('#login-view');
const playerView = $('#player-view');
const audio = $('#audio');
const VISUAL_DISPLAY_FPS = 60;
const VISUAL_SMOOTHING = 0;
const LYRICS_TICK_MS = 33;
const WEB_PREFERENCES_KEY = 'fredplayer.web.preferences.v3';
const DEVICE_TOKEN_KEY = 'fredplayer.web.remembered-device.v1';
const LOGIN_USERNAME_KEY = 'fredplayer.web.login-username.v1';
const launchParameters = new URLSearchParams(window.location.search);
document.body.classList.add(launchParameters.get('fluxa') === '1' ? 'fluxa-theme' : 'fredplayer-theme');

function isFluxaTizen() {
  if (/Tizen/i.test(navigator.userAgent) || 'tizen' in window) return true;
  const parameters = new URLSearchParams(window.location.search);
  if (parameters.get('platform') === 'tizen') return true;
  try {
    return new URL(parameters.get('return') || '', window.location.origin)
      .searchParams.get('platform') === 'tizen';
  } catch (_error) { return false; }
}

function installTizenLoginKeyboard() {
  if (!isFluxaTizen()) return;
  document.body.classList.add('tizen-tv');
  const loginFields = [$('#username'), $('#password')];
  const fields = loginFields.concat([$('#search')]);
  // Read-only suppresses Samsung's software keyboard. The physical keyboard
  // is applied by the handler below, so the fields remain fully editable with
  // the attached wireless keyboard without trapping remote navigation.
  fields.forEach((field) => {
    field.setAttribute('inputmode', 'none');
    field.readOnly = true;
  });
  $('#password').type = 'text';
  $('#password-field').classList.add('showing');
  $('#password-toggle').setAttribute('aria-pressed', 'true');
  $('#password-toggle').setAttribute('aria-label', 'Hide password');
  $('#password-toggle').setAttribute('title', 'Hide password');
  const keyboardToggle = $('#tv-keyboard-toggle');

  function setOnScreenKeyboard(enabled) {
    loginFields.forEach((field) => {
      field.readOnly = !enabled;
      field.setAttribute('inputmode', enabled ? 'text' : 'none');
    });
    keyboardToggle.classList.toggle('active', enabled);
    keyboardToggle.setAttribute('aria-pressed', String(enabled));
    keyboardToggle.setAttribute('aria-label', enabled ? 'Use wireless keyboard' : 'Use on-screen keyboard');
    keyboardToggle.setAttribute('title', enabled ? 'Use wireless keyboard' : 'Use on-screen keyboard');
    $('#tv-login-hint').textContent = enabled
      ? 'On-screen keyboard enabled. Select Username or Password with the remote.'
      : 'Password is visible on this TV. Type it with your wireless keyboard, then press Enter.';
    if (enabled) {
      const target = $('#password').value ? $('#username') : $('#password');
      window.setTimeout(() => target.focus(), 0);
    } else {
      keyboardToggle.focus();
    }
  }
  keyboardToggle.addEventListener('click', () => {
    setOnScreenKeyboard(keyboardToggle.getAttribute('aria-pressed') !== 'true');
  });
  const searchKeyboardToggle = $('#search-keyboard-toggle');
  searchKeyboardToggle.addEventListener('click', () => {
    const search = $('#search');
    const enabled = searchKeyboardToggle.getAttribute('aria-pressed') !== 'true';
    search.readOnly = !enabled;
    search.setAttribute('inputmode', enabled ? 'search' : 'none');
    searchKeyboardToggle.classList.toggle('active', enabled);
    searchKeyboardToggle.setAttribute('aria-pressed', String(enabled));
    searchKeyboardToggle.setAttribute('aria-label', enabled ? 'Use physical keyboard' : 'Use on-screen keyboard');
    searchKeyboardToggle.setAttribute('title', enabled ? 'Use physical keyboard' : 'Use on-screen keyboard');
    if (enabled) window.setTimeout(() => search.focus(), 0);
    else searchKeyboardToggle.focus();
  });

  function legacyKeyboardCharacter(event) {
    const code = event.which || event.keyCode || 0;
    if (code >= 65 && code <= 90) {
      const letter = String.fromCharCode(code);
      return event.shiftKey ? letter : letter.toLowerCase();
    }
    if (code >= 48 && code <= 57) {
      return event.shiftKey ? ')!@#$%^&*('[code - 48] : String.fromCharCode(code);
    }
    if (code >= 96 && code <= 105) return String(code - 96);
    if (code === 32) return ' ';
    const plain = { 186: ';', 187: '=', 188: ',', 189: '-', 190: '.', 191: '/', 192: '`', 219: '[', 220: '\\', 221: ']', 222: "'" };
    const shifted = { 186: ':', 187: '+', 188: '<', 189: '_', 190: '>', 191: '?', 192: '~', 219: '{', 220: '|', 221: '}', 222: '"' };
    return (event.shiftKey ? shifted : plain)[code] || '';
  }

  function replaceSelection(field, replacement, start, end) {
    if (typeof field.setRangeText === 'function') {
      field.setRangeText(replacement, start, end, 'end');
    } else {
      field.value = field.value.slice(0, start) + replacement + field.value.slice(end);
    }
    field.dispatchEvent(new Event('input', { bubbles: true }));
  }

  fields.forEach((field) => {
    field.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.keyCode === 13) {
        event.preventDefault();
        if (typeof $('#login-form').requestSubmit === 'function') $('#login-form').requestSubmit();
        else $('#login-form').dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
        return;
      }
      if (event.ctrlKey || event.altKey || event.metaKey) return;
      const before = field.value;
      const start = Number.isInteger(field.selectionStart) ? field.selectionStart : before.length;
      const end = Number.isInteger(field.selectionEnd) ? field.selectionEnd : start;
      const key = event.key && event.key.length === 1
        ? event.key
        : (event.key === 'Backspace' || event.key === 'Delete'
          ? event.key : legacyKeyboardCharacter(event));
      if (!key) return;
      if (!(key.length === 1 || key === 'Backspace' || key === 'Delete')) return;

      if (field.readOnly) {
        event.preventDefault();
        if (key.length === 1) replaceSelection(field, key, start, end);
        else if (key === 'Backspace' && (start !== end || start > 0)) {
          replaceSelection(field, '', start !== end ? start : start - 1, end);
        } else if (key === 'Delete' && (start !== end || end < before.length)) {
          replaceSelection(field, '', start, start !== end ? end : end + 1);
        }
        return;
      }

      // Samsung normally edits the field after keydown. If it does, leave the
      // native result alone. Some Tizen builds deliver the physical-keyboard
      // event but skip that edit; only then apply the missing edit ourselves.
      window.setTimeout(() => {
        if (field.value !== before || document.activeElement !== field) return;
        if (key.length === 1) replaceSelection(field, key, start, end);
        else if (key === 'Backspace' && (start !== end || start > 0)) {
          replaceSelection(field, '', start !== end ? start : start - 1, end);
        } else if (key === 'Delete' && (start !== end || end < before.length)) {
          replaceSelection(field, '', start, start !== end ? end : end + 1);
        }
      }, 0);
    }, true);
  });

  $('#login-form').addEventListener('keydown', (event) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const controls = [$('#username'), $('#password'), $('#password-toggle'), keyboardToggle, $('#remember-device'),
      $('#login-form button[type="submit"]')];
    const index = controls.indexOf(document.activeElement);
    if (index < 0) return;
    const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
    if (next < 0 || next >= controls.length) return;
    event.preventDefault();
    controls[next].focus();
  });
}

function installTizenRemoteNavigation() {
  if (!isFluxaTizen()) return;
  const selector = 'a[href]:not([hidden]), button:not([disabled]):not([hidden]), input:not([disabled]):not([hidden]), select:not([disabled]):not([hidden]), summary';

  function visible(element) {
    if (!element || element.offsetParent === null) return false;
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function center(element) {
    const rect = element.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  }

  function focusables() {
    return [...document.querySelectorAll(selector)].filter(visible);
  }

  function focusRemote(element) {
    if (!element) return;
    element.focus();
    try { element.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'auto' }); }
    catch (_error) { element.scrollIntoView(false); }
  }

  function adjustRange(element, direction) {
    const step = Number(element.step) || 1;
    const minimum = Number(element.min);
    const maximum = Number(element.max);
    element.value = String(Math.max(minimum, Math.min(maximum, Number(element.value) + step * direction)));
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function move(direction) {
    const nodes = focusables();
    if (!nodes.length) return;
    const current = document.activeElement;
    if (!nodes.includes(current)) {
      focusRemote(nodes[0]);
      return;
    }
    if (current.matches('input[type="range"]') && (direction === 'ArrowLeft' || direction === 'ArrowRight')) {
      adjustRange(current, direction === 'ArrowRight' ? 1 : -1);
      return;
    }
    const source = center(current);
    let best = null;
    let bestScore = Infinity;
    nodes.forEach((candidate) => {
      if (candidate === current) return;
      const target = center(candidate);
      const dx = target.x - source.x;
      const dy = target.y - source.y;
      let primary;
      let secondary;
      if (direction === 'ArrowRight' && dx > 3) { primary = dx; secondary = Math.abs(dy); }
      else if (direction === 'ArrowLeft' && dx < -3) { primary = -dx; secondary = Math.abs(dy); }
      else if (direction === 'ArrowDown' && dy > 3) { primary = dy; secondary = Math.abs(dx); }
      else if (direction === 'ArrowUp' && dy < -3) { primary = -dy; secondary = Math.abs(dx); }
      else return;
      const score = primary + secondary * 2.4;
      if (score < bestScore) { bestScore = score; best = candidate; }
    });
    focusRemote(best);
  }

  document.addEventListener('keydown', (event) => {
    if (event.defaultPrevented) return;
    if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter', 'Escape'].includes(event.key)
        && event.keyCode !== 10009) return;
    const current = document.activeElement;
    const editingText = current?.matches('input[type="text"], input[type="password"], input[type="search"]')
      && !current.readOnly;
    if (editingText && !['ArrowUp', 'ArrowDown', 'Escape'].includes(event.key) && event.keyCode !== 10009) return;
    if (event.key === 'Enter') {
      if (current?.matches('button, a, summary')) {
        event.preventDefault();
        current.click();
      } else if (current?.matches('input[type="checkbox"]')) {
        event.preventDefault();
        current.click();
      } else if (current?.matches('select')) {
        event.preventDefault();
        current.click();
      }
      return;
    }
    if (event.key === 'Escape' || event.keyCode === 10009) {
      if (editingText && $('#tv-keyboard-toggle')?.getAttribute('aria-pressed') === 'true') {
        event.preventDefault();
        $('#tv-keyboard-toggle').click();
        return;
      }
      if (editingText && $('#search-keyboard-toggle')?.getAttribute('aria-pressed') === 'true') {
        event.preventDefault();
        $('#search-keyboard-toggle').click();
        return;
      }
      const returnLink = $('#fluxa-return');
      if (returnLink && new URLSearchParams(window.location.search).get('fluxa') === '1') {
        event.preventDefault();
        returnToFluxa();
      }
      return;
    }
    event.preventDefault();
    move(event.key);
  });
}

function configureFluxaReturn() {
  const target = $('#fluxa-return');
  const parameters = new URLSearchParams(window.location.search);
  if (parameters.get('fluxa') !== '1') return;
  const requested = parameters.get('return') || '/fluxa/';
  try {
    const destination = new URL(requested, window.location.origin);
    if (!['http:', 'https:'].includes(destination.protocol)) return;
    target.href = destination.href;
    target.hidden = false;
    if (parameters.get('embedded') === '1') {
      target.addEventListener('click', (event) => {
        event.preventDefault();
        window.parent.postMessage({ type: 'fluxa-fredplayer-close' }, '*');
      });
    }
  } catch (_error) {}
}

function returnToFluxa() {
  const parameters = new URLSearchParams(window.location.search);
  if (parameters.get('embedded') === '1' && window.parent !== window) {
    window.parent.postMessage({ type: 'fluxa-fredplayer-close' }, '*');
    return;
  }
  const returnLink = $('#fluxa-return');
  if (returnLink) window.location.assign(returnLink.href);
}

function fluxaReturnDestination() {
  const parameters = new URLSearchParams(window.location.search);
  if (parameters.get('fluxa') !== '1') return null;
  try {
    const destination = new URL(parameters.get('return') || '/fluxa/', window.location.origin);
    return ['http:', 'https:'].includes(destination.protocol) ? destination : null;
  } catch (_error) { return null; }
}

async function completeFluxaAuthorization() {
  const parameters = new URLSearchParams(window.location.search);
  const destination = fluxaReturnDestination();
  if (!destination || parameters.get('authorize') !== '1') return false;
  const response = await fetch('./auth/fluxa-grant', {
    method: 'POST',
    credentials: 'same-origin',
  });
  const result = await response.json();
  if (!response.ok || !result.grant) throw new Error(result.error || 'Could not authorize Fluxa');
  destination.searchParams.set('music', '1');
  destination.searchParams.set('fred_grant', result.grant);
  window.location.replace(destination.href);
  return true;
}

const state = {
  library: [],
  libraryByPath: new Map(),
  playlists: [],
  displayedTracks: [],
  queue: [],
  currentIndex: -1,
  sourceName: 'All music',
  queueSourceName: '',
  sidePanel: '',
  shuffle: true,
  repeatMode: 'none',
  volume: 1,
  leveling: true,
  visualization: false,
  visualData: null,
  visualTrackPath: '',
  visualAnimation: 0,
  visualLastPaint: 0,
  visualSpectrum: new Float32Array(0),
  lyrics: [],
  lyricsTrackPath: '',
  activeLyricIndex: -1,
  activeLyricWordIndex: -1,
  lyricsTimer: 0,
  streamBaseOffset: 0,
  resumePosition: 0,
  trackDuration: 0,
  seekingPosition: null,
  durationCache: new Map(),
  playGeneration: 0,
  diagnostics: [],
  mediaSessionActions: [],
  visibleLimit: 100,
};

function encodePath(value) {
  return value.split('/').map(encodeURIComponent).join('/');
}

function api(path, options = {}) {
  return fetch(`./api/${path}`, {
    credentials: 'same-origin',
    ...options,
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
  }).then(async (response) => {
    if (response.status === 401) {
      showLogin();
      throw new Error('Your FredPlayer session has expired.');
    }
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try { message = (await response.json()).error || message; } catch (_error) {}
      throw new Error(message);
    }
    return response;
  });
}

function logDiagnostic(event, details = {}) {
  state.diagnostics.push({ at: new Date().toISOString(), event, ...details });
  if (state.diagnostics.length > 250) state.diagnostics.shift();
  $('#diagnostics').textContent = state.diagnostics
    .map((entry) => `${entry.at}  ${entry.event}${Object.keys(entry).length > 2 ? `  ${JSON.stringify(Object.fromEntries(Object.entries(entry).filter(([key]) => !['at', 'event'].includes(key))))}` : ''}`)
    .join('\n');
}

function teslaModeEnabled() {
  return document.body.classList.contains('tesla');
}

function logTeslaControl(source, action, details = {}) {
  if (!teslaModeEnabled()) return;
  const track = state.queue[state.currentIndex];
  logDiagnostic('tesla-control', {
    source,
    action,
    ...details,
    visibility: document.visibilityState,
    paused: audio.paused,
    position: Number(playbackPosition().toFixed(3)),
    track: track?.path || '',
  });
}

function updateTeslaModeUi(enabled) {
  document.body.classList.toggle('tesla', enabled);
  $('#tesla-mode').classList.toggle('active', enabled);
  $('#tesla-control-status').hidden = !enabled;
}

function showLogin(message = '') {
  playerView.hidden = true;
  loginView.hidden = false;
  $('#login-error').textContent = message;
  audio.pause();
  try { $('#username').value = localStorage.getItem(LOGIN_USERNAME_KEY) || $('#username').value; } catch (_error) {}
  const target = $('#username').value ? $('#password') : $('#username');
  window.setTimeout(() => target.focus(), 0);
}

function showPlayer() {
  loginView.hidden = true;
  playerView.hidden = false;
}

async function rememberAuthenticatedDevice() {
  try {
    if (localStorage.getItem(DEVICE_TOKEN_KEY)) return;
    const response = await fetch('./auth/device/enroll', {
      method: 'POST',
      credentials: 'same-origin',
    });
    if (!response.ok) return;
    const result = await response.json();
    if (result.deviceToken) localStorage.setItem(DEVICE_TOKEN_KEY, result.deviceToken);
  } catch (_error) {}
}

function formatTime(value) {
  if (!Number.isFinite(value) || value < 0) return '0:00';
  const seconds = Math.floor(value % 60).toString().padStart(2, '0');
  return `${Math.floor(value / 60)}:${seconds}`;
}

function fileName(path) {
  const parts = String(path || '').split('/');
  return parts.length ? parts[parts.length - 1] : '';
}

// Samsung's Tizen 6.5 browser is Chromium 85. Element.replaceChildren and
// Array.prototype.at arrived just after that release, so using either one
// makes a successful login look like a failed login when library rendering
// begins. Keep this small compatibility helper local to the WebUI.
function replaceChildren(element, ...children) {
  while (element.firstChild) element.removeChild(element.firstChild);
  children.forEach((child) => element.appendChild(child));
}

function playbackPosition() {
  if (Number.isFinite(state.seekingPosition)) return state.seekingPosition;
  if (!audio.src && state.currentIndex >= 0) return state.resumePosition;
  return state.streamBaseOffset + (Number.isFinite(audio.currentTime) ? audio.currentTime : 0);
}

function subtitle(track) {
  return [track?.artist, track?.album].filter(Boolean).join(' · ') || 'Unknown artist';
}

function updatePlayingPlaylist() {
  const name = state.currentIndex >= 0 && state.queue.length
    ? (state.queueSourceName || 'Current queue')
    : 'None';
  const sourceKind = name === 'All music' ? 'Library' : (name === 'Current queue' ? 'Queue' : 'Playlist');
  $('#playing-playlist').textContent = `${sourceKind} · ${name}`;
}

function shuffled(values) {
  const result = [...values];
  for (let index = result.length - 1; index > 0; index -= 1) {
    const other = Math.floor(Math.random() * (index + 1));
    [result[index], result[other]] = [result[other], result[index]];
  }
  return result;
}

function savedState() {
  try {
    return {
      ...JSON.parse(localStorage.getItem('fredplayer.web.queue') || '{}'),
      ...JSON.parse(localStorage.getItem('fredplayer.web.preferences') || '{}'),
      ...JSON.parse(localStorage.getItem('fredplayer.web.position') || '{}'),
      ...JSON.parse(localStorage.getItem(WEB_PREFERENCES_KEY) || '{}'),
    };
  } catch (_error) { return {}; }
}

function persistPreferences() {
  const snapshot = {
    sourceName: state.sourceName,
    shuffle: state.shuffle,
    repeatMode: state.repeatMode,
    volume: state.volume,
    leveling: state.leveling,
    visualization: state.visualization,
    tesla: document.body.classList.contains('tesla'),
    sidePanel: state.sidePanel,
  };
  try { localStorage.setItem(WEB_PREFERENCES_KEY, JSON.stringify(snapshot)); } catch (_error) {}
}

function saveQueueState() {
  const current = state.queue[state.currentIndex];
  try {
    localStorage.setItem('fredplayer.web.queue', JSON.stringify({
      queue: state.queue.map((track) => track.path),
      currentPath: current?.path || '',
      queueSourceName: state.queueSourceName,
    }));
  } catch (_error) {}
}

function savePreferences() {
  try {
    localStorage.setItem('fredplayer.web.preferences', JSON.stringify({
      shuffle: state.shuffle,
      repeatMode: state.repeatMode,
      volume: state.volume,
      leveling: state.leveling,
      visualization: state.visualization,
      tesla: document.body.classList.contains('tesla'),
      sourceName: state.sourceName,
      sidePanel: state.sidePanel,
    }));
  } catch (_error) {}
  persistPreferences();
}

function savePosition() {
  const current = state.queue[state.currentIndex];
  try {
    localStorage.setItem('fredplayer.web.position', JSON.stringify({
      currentPath: current?.path || '',
      position: playbackPosition(),
    }));
  } catch (_error) {}
}

function trackRow(track) {
  const row = document.createElement('div');
  row.className = 'track-row';
  row.setAttribute('role', 'listitem');
  const details = document.createElement('div');
  details.className = 'track-main';
  const title = document.createElement('div');
  title.className = 'track-title';
  title.textContent = track.title || fileName(track.path);
  const meta = document.createElement('div');
  meta.className = 'track-meta';
  meta.textContent = subtitle(track);
  details.append(title, meta);
  const play = document.createElement('button');
  play.type = 'button';
  play.textContent = 'Play';
  play.addEventListener('click', () => playFromSource(track));
  row.append(details, play);
  return row;
}

function renderTracks() {
  const query = $('#search').value.trim().toLocaleLowerCase();
  const filtered = query
    ? state.displayedTracks.filter((track) => `${track.title}\n${track.artist}\n${track.album}`.toLocaleLowerCase().includes(query))
    : state.displayedTracks;
  const shown = filtered.slice(0, state.visibleLimit);
  const fragment = document.createDocumentFragment();
  shown.forEach((track) => fragment.append(trackRow(track)));
  if (shown.length < filtered.length) {
    const more = document.createElement('button');
    more.type = 'button';
    more.className = 'secondary wide';
    more.textContent = `Show 100 more (${filtered.length - shown.length} remaining)`;
    more.addEventListener('click', () => { state.visibleLimit += 100; renderTracks(); });
    fragment.append(more);
  }
  const list = $('#track-list');
  replaceChildren(list, fragment);
  if (!shown.length) list.textContent = 'No matching tracks.';
}

function renderQueue() {
  const fragment = document.createDocumentFragment();
  const appendLabel = (text, parent = fragment) => {
    const label = document.createElement('div');
    label.className = 'queue-section-label';
    label.textContent = text;
    parent.append(label);
  };
  const appendTrack = (track, index, history = false, parent = fragment) => {
    const row = document.createElement('div');
    row.className = `queue-row${index === state.currentIndex ? ' current' : ''}${history ? ' history' : ''}`;
    const details = document.createElement('div');
    details.className = 'track-main';
    const title = document.createElement('div');
    title.className = 'track-title';
    title.textContent = `${index === state.currentIndex ? 'Now · ' : history ? 'Played · ' : ''}${track.title || fileName(track.path)}`;
    const meta = document.createElement('div');
    meta.className = 'track-meta';
    meta.textContent = subtitle(track);
    details.append(title, meta);
    const play = document.createElement('button');
    play.type = 'button';
    play.textContent = history ? 'Replay' : 'Play';
    play.addEventListener('click', () => playTrack(index));
    row.append(details, play);
    parent.append(row);
  };
  if (state.currentIndex > 0) {
    const historyStart = Math.max(0, state.currentIndex - 25);
    const historySection = document.createElement('section');
    historySection.className = 'queue-history';
    appendLabel(`History · ${state.currentIndex} played`, historySection);
    const history = state.queue.slice(historyStart, state.currentIndex);
    history.reverse().forEach((track, offset) => appendTrack(
      track,
      state.currentIndex - 1 - offset,
      true,
      historySection,
    ));
    fragment.append(historySection);
  }
  if (state.currentIndex >= 0) appendLabel('Now and next');
  const start = Math.max(0, state.currentIndex);
  state.queue.slice(start, start + 50).forEach((track, offset) => appendTrack(track, start + offset));
  const list = $('#queue-list');
  replaceChildren(list, fragment);
  if (!state.queue.length) list.textContent = 'The queue is empty.';
}

function applyVolume() {
  audio.volume = Math.max(0, Math.min(1, state.volume));
}

function parseVisualization(buffer) {
  const headerBytes = 24;
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < headerBytes) {
    throw new Error('Visualization data is incomplete.');
  }
  const view = new DataView(buffer);
  const magic = view.getUint32(0, false);
  const version = view.getUint32(4, false);
  const fps = view.getUint32(8, false);
  const waveformPoints = view.getUint32(12, false);
  const bars = view.getUint32(16, false);
  const frameCount = view.getUint32(20, false);
  const stride = waveformPoints + bars;
  if (magic !== 0x46565a32 || version !== 2 || fps < 1
      || waveformPoints !== 96 || bars !== 64 || frameCount < 1
      || headerBytes + frameCount * stride !== buffer.byteLength) {
    throw new Error('Visualization data has an unsupported format.');
  }
  return { fps, waveformPoints, bars, frameCount, stride, payload: new Uint8Array(buffer, headerBytes) };
}

function drawVisualization() {
  if (!state.visualization || !state.visualData) return;
  const canvas = $('#visualization');
  const width = Math.max(1, Math.floor(canvas.clientWidth));
  const height = Math.max(1, Math.floor(canvas.clientHeight));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const context = canvas.getContext('2d', { alpha: false });
  const data = state.visualData;
  const frame = Math.min(data.frameCount - 1, Math.max(0, Math.floor(playbackPosition() * data.fps)));
  const offset = frame * data.stride;
  const padding = 20;
  const waveformTop = padding;
  const waveformHeight = Math.max(40, height * 0.45 - padding);
  const waveformCenter = waveformTop + waveformHeight / 2;
  const spectrumTop = height * 0.54;
  const spectrumHeight = Math.max(30, height - spectrumTop - padding);

  context.fillStyle = '#020908';
  context.fillRect(0, 0, width, height);
  context.strokeStyle = '#25434a';
  context.lineWidth = 1;
  context.strokeRect(padding, waveformTop, width - padding * 2, waveformHeight);
  context.strokeRect(padding, spectrumTop, width - padding * 2, spectrumHeight);
  context.beginPath();
  context.moveTo(padding, waveformCenter);
  context.lineTo(width - padding, waveformCenter);
  context.stroke();

  context.strokeStyle = '#29d3ae';
  context.lineWidth = 1.5;
  context.beginPath();
  for (let point = 0; point < data.waveformPoints; point += 1) {
    const raw = data.payload[offset + point];
    const sample = (raw > 127 ? raw - 256 : raw) / 127;
    const x = padding + point * (width - padding * 2) / (data.waveformPoints - 1);
    const y = waveformCenter - sample * waveformHeight * 0.44;
    if (point === 0) context.moveTo(x, y); else context.lineTo(x, y);
  }
  context.stroke();

  const availableWidth = width - padding * 2;
  const step = availableWidth / data.bars;
  const barWidth = Math.max(1, step - 1);
  for (let bar = 0; bar < data.bars; bar += 1) {
    const target = Math.min(1, data.payload[offset + data.waveformPoints + bar] / 100);
    const amount = state.visualSpectrum[bar] * VISUAL_SMOOTHING + target * (1 - VISUAL_SMOOTHING);
    state.visualSpectrum[bar] = amount;
    const barHeight = Math.max(1, amount * (spectrumHeight - 4));
    context.fillStyle = bar < data.bars / 2 ? '#5be1c2' : '#178c77';
    context.fillRect(padding + bar * step, spectrumTop + spectrumHeight - barHeight, barWidth, barHeight);
  }
}

function stopVisualizationLoop() {
  if (state.visualAnimation) cancelAnimationFrame(state.visualAnimation);
  state.visualAnimation = 0;
}

function startVisualizationLoop() {
  if (!state.visualization || audio.paused || state.visualAnimation) return;
  const tick = (now) => {
    state.visualAnimation = 0;
    if (!state.visualization || audio.paused) return;
    if (now - state.visualLastPaint >= 1000 / VISUAL_DISPLAY_FPS) {
      drawVisualization();
      state.visualLastPaint = now;
    }
    state.visualAnimation = requestAnimationFrame(tick);
  };
  state.visualAnimation = requestAnimationFrame(tick);
}

async function loadVisualization(track) {
  if (!state.visualization || !track) return;
  const requestedPath = track.path;
  state.visualTrackPath = requestedPath;
  state.visualData = null;
  $('#visualization-status').textContent = 'Loading server-precomputed frames…';
  try {
    const response = await api(`visual/${encodePath(requestedPath)}`);
    const visual = parseVisualization(await response.arrayBuffer());
    if (!state.visualization || state.visualTrackPath !== requestedPath) return;
    state.visualData = visual;
    state.visualSpectrum = new Float32Array(visual.bars);
    state.visualLastPaint = 0;
    $('#visualization-status').textContent = `${visual.fps} FPS · 90 ms waveform · 2048 FFT · ${visual.bars} bars · 0% smooth · log FFT · no browser FFT`;
    drawVisualization();
    startVisualizationLoop();
  } catch (error) {
    if (state.visualTrackPath !== requestedPath) return;
    $('#visualization-status').textContent = error.message;
  }
}

function setVisualizationEnabled(enabled) {
  state.visualization = Boolean(enabled);
  $('#visualization-panel').hidden = !state.visualization;
  $('#visualization-toggle').classList.toggle('active', state.visualization);
  $('#visualization-toggle').setAttribute('aria-pressed', String(state.visualization));
  $('#visualization-toggle').textContent = state.visualization ? 'Visualization on' : 'Visualization off';
  stopVisualizationLoop();
  if (state.visualization) {
    const track = state.queue[state.currentIndex];
    if (track) loadVisualization(track);
    else $('#visualization-status').textContent = 'Choose a track to begin.';
  } else {
    state.visualData = null;
    state.visualTrackPath = '';
    state.visualSpectrum = new Float32Array(0);
    const canvas = $('#visualization');
    canvas.width = 1;
    canvas.height = 1;
  }
  if (state.lyrics.length) {
    renderLyrics();
    updateLyricsHighlight(true, false);
  }
  syncLyricsTimer();
  savePreferences();
}

function chooseLyricsSection(response) {
  const sections = response?.sections;
  if (!sections || typeof sections !== 'object') return [];
  if (Array.isArray(sections.Original)) return sections.Original;
  const first = Object.values(sections).find(Array.isArray);
  return first || [];
}

function renderLyrics() {
  const list = $('#lyrics-list');
  list.classList.toggle('odometer-lyrics', state.visualization);
  if (!state.lyrics.length) {
    list.innerHTML = '<p class="muted">Lyrics are not available for this track.</p>';
    return;
  }
  const fragment = document.createDocumentFragment();
  state.lyrics.forEach((phrase, index) => {
    const line = document.createElement('p');
    line.className = `lyric-line${index === state.activeLyricIndex ? ' active' : ''}`;
    line.dataset.index = String(index);
    if (Array.isArray(phrase.words) && phrase.words.length) {
      phrase.words.forEach((word, wordIndex) => {
        if (wordIndex) line.append(' ');
        const span = document.createElement('span');
        span.className = 'lyric-word';
        span.dataset.wordIndex = String(wordIndex);
        span.textContent = word.text;
        line.append(span);
      });
    } else {
      line.textContent = phrase.text;
    }
    fragment.append(line);
  });
  replaceChildren(list, fragment);
}

function sungWordIndex(phrase, position) {
  if (!Array.isArray(phrase?.words)) return -1;
  let sung = -1;
  phrase.words.forEach((word, index) => {
    // Highlight at the word's own onset. Waiting for the following word made
    // the display visibly one word late and could skip a line's final word.
    if (word.time <= position) sung = index;
  });
  return sung;
}

function stopLyricsTimer() {
  if (state.lyricsTimer) clearInterval(state.lyricsTimer);
  state.lyricsTimer = 0;
}

function syncLyricsTimer() {
  stopLyricsTimer();
  if (audio.paused || $('#lyrics-panel').hidden || !state.lyrics.length) return;
  state.lyricsTimer = window.setInterval(updateLyricsHighlight, LYRICS_TICK_MS);
}

function updateLyricsHighlight(force = false, scroll = true) {
  if ($('#lyrics-panel').hidden || !state.lyrics.length) return;
  const position = playbackPosition();
  let active = state.lyrics.findIndex((phrase) => position >= phrase.start && position < phrase.end);
  if (active < 0) {
    for (let index = state.lyrics.length - 1; index >= 0; index -= 1) {
      if (position >= state.lyrics[index].start) { active = index; break; }
    }
  }
  const activeWord = active >= 0
    ? sungWordIndex(state.lyrics[active], position)
    : -1;
  const phraseChanged = active !== state.activeLyricIndex;
  if (!force && !phraseChanged && activeWord === state.activeLyricWordIndex) return;

  state.activeLyricIndex = active;
  state.activeLyricWordIndex = activeWord;
  const list = $('#lyrics-list');
  const lines = [...list.querySelectorAll('.lyric-line')];
  if (force || phraseChanged) {
    lines.forEach((candidate, index) => {
      candidate.classList.toggle('active', index === active);
      candidate.classList.toggle('past', state.visualization && active >= 0 && index < active);
      candidate.classList.toggle('future', state.visualization && active >= 0 && index > active);
    });
  }
  const line = lines[active];
  if (line) {
    line.querySelectorAll('.lyric-word').forEach((word, index) => {
      word.classList.toggle('sung', index <= activeWord);
    });
    if (phraseChanged && state.visualization) {
      line.classList.remove('odometer-enter');
      void line.offsetWidth;
      line.classList.add('odometer-enter');
      line.addEventListener('animationend', () => line.classList.remove('odometer-enter'), { once: true });
    }
    if (phraseChanged && scroll) {
      const listRect = list.getBoundingClientRect();
      const lineRect = line.getBoundingClientRect();
      const top = list.scrollTop + lineRect.top + lineRect.height / 2
        - listRect.top - listRect.height / 2;
      list.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
    }
  }
}

async function loadLyrics(track) {
  state.lyrics = [];
  state.activeLyricIndex = -1;
  state.activeLyricWordIndex = -1;
  state.lyricsTrackPath = track?.path || '';
  $('#lyrics-language').textContent = '';
  $('#lyrics-list').innerHTML = '<p class="muted">Loading lyrics…</p>';
  if (!track) return;
  const requestedPath = track.path;
  try {
    const response = await (await api(`lyrics/${encodePath(requestedPath)}`)).json();
    if (state.lyricsTrackPath !== requestedPath) return;
    state.lyrics = chooseLyricsSection(response);
    $('#lyrics-language').textContent = response.language || '';
    renderLyrics();
    updateLyricsHighlight();
    syncLyricsTimer();
  } catch (_error) {
    if (state.lyricsTrackPath !== requestedPath) return;
    renderLyrics();
  }
}

function setSidePanel(panelName) {
  state.sidePanel = panelName === 'queue' || panelName === 'lyrics' ? panelName : '';
  const queueOpen = state.sidePanel === 'queue';
  const lyricsOpen = state.sidePanel === 'lyrics';
  $('#queue-panel').hidden = !queueOpen;
  $('#lyrics-panel').hidden = !lyricsOpen;
  $('#content-grid').classList.toggle('side-open', queueOpen || lyricsOpen);
  $('#queue-toggle').classList.toggle('active', queueOpen);
  $('#queue-toggle').setAttribute('aria-pressed', String(queueOpen));
  $('#lyrics-toggle').classList.toggle('active', lyricsOpen);
  $('#lyrics-toggle').setAttribute('aria-pressed', String(lyricsOpen));
  if (lyricsOpen) loadLyrics(state.queue[state.currentIndex]);
  else stopLyricsTimer();
  savePreferences();
}

function showSidePanel(panelName) {
  setSidePanel(state.sidePanel === panelName ? '' : panelName);
}

async function audioInfoFor(track) {
  if (state.durationCache.has(track.path)) return state.durationCache.get(track.path);
  const pending = api(`audio-info/${encodePath(track.path)}`)
    .then((response) => response.json())
    .catch((error) => {
      state.durationCache.delete(track.path);
      throw error;
    });
  state.durationCache.set(track.path, pending);
  return pending;
}

function processedAudioUrl(track, position) {
  const format = audio.canPlayType('audio/flac') ? 'flac' : 'mp3';
  const query = new URLSearchParams({
    start: Math.max(0, Number(position) || 0).toFixed(3),
    leveling: state.leveling ? '1' : '0',
    format,
  });
  return new URL(`./api/audio/${encodePath(track.path)}?${query}`, window.location.href).href;
}

function preauthorizeNext() {
  const nextTrack = state.queue[state.currentIndex + 1] || (state.queue.length ? state.queue[0] : null);
  if (!nextTrack) return;
  audioInfoFor(nextTrack)
    .then(() => logDiagnostic('next-track-ready', { path: nextTrack.path }))
    .catch((error) => logDiagnostic('next-track-prepare-failed', { message: error.message }));
}

function updateMediaSession(track) {
  if (!('mediaSession' in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title || fileName(track.path),
    artist: track.artist || '',
    album: track.album || '',
    artwork: [{ src: new URL(`./api/artwork/${encodePath(track.path)}`, window.location.href).href }],
  });
}

async function playTrack(index, position = 0, shouldPlay = true) {
  if (index < 0 || index >= state.queue.length) return;
  if (!audio.paused) savePosition();
  audio.pause();
  const generation = ++state.playGeneration;
  state.currentIndex = index;
  const track = state.queue[index];
  updatePlayingPlaylist();
  $('#track-title').textContent = track.title || fileName(track.path);
  $('#track-subtitle').textContent = subtitle(track);
  $('#playback-status').textContent = 'Preparing server-processed stream…';
  $('#artwork').src = `./api/artwork/${encodePath(track.path)}`;
  $('#artwork').onerror = () => { $('#artwork').src = './assets/no-album-art.png'; };
  renderQueue();
  updateMediaSession(track);
  if (state.visualization) loadVisualization(track);
  if (!$('#lyrics-panel').hidden) loadLyrics(track);
  logDiagnostic('track-prepare', { path: track.path, requestedPosition: position });
  try {
    const sourcePosition = Math.max(0, Number(position) || 0);
    state.streamBaseOffset = sourcePosition;
    state.resumePosition = sourcePosition;
    state.seekingPosition = null;
    $('#seek').value = sourcePosition;
    $('#elapsed').textContent = formatTime(sourcePosition);
    saveQueueState();
    applyVolume();
    audio.src = processedAudioUrl(track, sourcePosition);
    audio.load();
    const playAttempt = shouldPlay ? audio.play().then(
      () => ({ error: null }),
      (error) => ({ error }),
    ) : Promise.resolve({ error: null });
    const [playResult, info] = await Promise.all([playAttempt, audioInfoFor(track)]);
    if (generation !== state.playGeneration || state.queue[state.currentIndex]?.path !== track.path) return;
    state.trackDuration = Number.isFinite(info.duration) ? info.duration : 0;
    $('#seek').max = state.trackDuration || 1;
    $('#duration').textContent = formatTime(state.trackDuration);
    if (playResult.error) throw playResult.error;
    else $('#playback-status').textContent = 'Paused';
    logDiagnostic('play-accepted', {
      serverDsp: state.leveling,
      sourcePosition,
      duration: state.trackDuration,
      format: audio.canPlayType('audio/flac') ? 'flac' : 'mp3',
    });
    preauthorizeNext();
  } catch (error) {
    const blocked = error?.name === 'NotAllowedError';
    $('#playback-status').textContent = blocked
      ? 'Ready — press the highlighted Play button once to allow sound in this browser.'
      : error.message;
    $('#play').classList.toggle('play-required', blocked);
    logDiagnostic('play-rejected', { name: error.name, message: error.message });
  }
}

function playFromSource(track) {
  const remaining = state.displayedTracks.filter((entry) => entry.path !== track.path);
  state.queue = [track, ...(state.shuffle ? shuffled(remaining) : remaining)];
  state.queueSourceName = state.sourceName;
  return playTrack(0);
}

function playFromLibrary(track) {
  applySourceTracks('All music', state.library, false);
  const remaining = state.library.filter((entry) => entry.path !== track.path);
  state.queue = [track, ...(state.shuffle ? shuffled(remaining) : remaining)];
  state.queueSourceName = 'All music';
  return playTrack(0);
}

window.FluxaFredPlayer = {
  playPath(path) {
    if (!path) return false;
    const track = state.libraryByPath.get(path) || {
      path,
      title: fileName(path).replace(/\.[^.]+$/, ''),
      artist: '',
      album: '',
    };
    showPlayer();
    playFromLibrary(track);
    return true;
  },
  stop() {
    stopPlayback();
  },
};

window.addEventListener('message', (event) => {
  if (event.source !== window.parent || !event.data) return;
  const destination = fluxaReturnDestination();
  if (!destination || event.origin !== destination.origin) return;
  if (event.data.type === 'fluxa-fredplayer-play') window.FluxaFredPlayer.playPath(event.data.path);
  else if (event.data.type === 'fluxa-fredplayer-stop') window.FluxaFredPlayer.stop();
});

async function playRequestedTrack() {
  const url = new URL(window.location.href);
  const requested = url.searchParams.get('play');
  if (!requested) return false;
  const track = state.libraryByPath.get(requested);
  url.searchParams.delete('play');
  window.history.replaceState({}, '', url.href);
  if (!track) {
    $('#playback-status').textContent = 'That track is no longer in the FredPlayer library.';
    return false;
  }
  await playFromLibrary(track);
  return true;
}

function playNext() {
  if (!state.queue.length) return;
  if (state.currentIndex + 1 < state.queue.length) {
    playTrack(state.currentIndex + 1);
  } else if (state.repeatMode === 'all') {
    playTrack(0);
  }
}

function playPrevious() {
  if (playbackPosition() > 5) {
    playTrack(state.currentIndex, 0);
    return;
  }
  if (!state.queue.length) return;
  if (state.currentIndex > 0) playTrack(state.currentIndex - 1);
  else if (state.repeatMode === 'all') playTrack(state.queue.length - 1);
  else playTrack(0);
}

function updateRepeatControl() {
  const repeat = $('#repeat');
  const label = state.repeatMode === 'one'
    ? 'Repeat one' : (state.repeatMode === 'all' ? 'Repeat all' : 'Repeat off');
  repeat.classList.toggle('active', state.repeatMode !== 'none');
  repeat.classList.toggle('repeat-one', state.repeatMode === 'one');
  repeat.setAttribute('aria-pressed', String(state.repeatMode !== 'none'));
  repeat.setAttribute('aria-label', label);
  repeat.title = label;
}

function cycleRepeatMode() {
  state.repeatMode = state.repeatMode === 'none'
    ? 'one' : (state.repeatMode === 'one' ? 'all' : 'none');
  updateRepeatControl();
  savePreferences();
}

function handleTrackEnded() {
  if (state.repeatMode === 'one' && state.currentIndex >= 0) {
    playTrack(state.currentIndex, 0);
    return;
  }
  if (state.currentIndex + 1 < state.queue.length || state.repeatMode === 'all') {
    playNext();
    return;
  }
  $('#playback-status').textContent = 'Finished';
  updateTransport();
  savePosition();
}

function stopPlayback() {
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  state.streamBaseOffset = 0;
  state.resumePosition = 0;
  state.seekingPosition = null;
  $('#seek').value = 0;
  $('#elapsed').textContent = '0:00';
  $('#playback-status').textContent = 'Stopped';
  savePosition();
}

function updateTransport() {
  $('#play').classList.toggle('playing', !audio.paused);
  $('#play').setAttribute('aria-label', audio.paused ? 'Play' : 'Pause');
  $('#play').title = audio.paused ? 'Play' : 'Pause';
}

function applySourceTracks(name, tracks, persist = true) {
  state.sourceName = name;
  state.displayedTracks = tracks;
  state.visibleLimit = 100;
  $('#library-source').classList.toggle('active', name === 'All music');
  $('#playlist-source').value = name === 'All music' ? '' : name;
  document.querySelectorAll('#playlist-buttons button').forEach((button) => {
    button.classList.toggle('active', button.dataset.playlistName === name);
    button.setAttribute('aria-pressed', String(button.dataset.playlistName === name));
  });
  $('#search').value = '';
  renderTracks();
  if (persist) savePreferences();
}

async function tracksForPlaylist(name) {
  const playlist = await (await api(`playlists/${encodeURIComponent(name)}`)).json();
  return {
    count: Array.isArray(playlist.tracks) ? playlist.tracks.length : 0,
    tracks: (Array.isArray(playlist.tracks) ? playlist.tracks : [])
      .map((trackPath) => state.libraryByPath.get(trackPath))
      .filter(Boolean),
  };
}

function stopForSourceChange() {
  if (state.currentIndex >= 0) savePosition();
  state.playGeneration += 1;
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  state.streamBaseOffset = 0;
  state.resumePosition = 0;
  state.trackDuration = 0;
  state.seekingPosition = null;
  $('#seek').value = 0;
  $('#elapsed').textContent = '0:00';
  $('#duration').textContent = '0:00';
}

async function startSource(name, tracks) {
  applySourceTracks(name, tracks);
  state.queue = state.shuffle ? shuffled(tracks) : [...tracks];
  state.queueSourceName = name;
  state.currentIndex = -1;
  updatePlayingPlaylist();
  renderQueue();
  saveQueueState();
  if (!state.queue.length) {
    $('#playback-status').textContent = `${name} has no playable tracks.`;
    return;
  }
  await playTrack(0);
}

async function selectPlaylist(name) {
  if (!name) return;
  stopForSourceChange();
  $('#playback-status').textContent = `Loading ${name}…`;
  try {
    const result = await tracksForPlaylist(name);
    await startSource(name, result.tracks);
    logDiagnostic('playlist-started', { name, tracks: result.count, playable: result.tracks.length });
  } catch (error) {
    $('#playback-status').textContent = error.message;
    logDiagnostic('playlist-open-failed', { name, message: error.message });
  }
}

async function loadLibrary() {
  $('#connection-status').textContent = 'Loading library…';
  const [library, playlists] = await Promise.all([
    (await api('library')).json(),
    (await api('playlists')).json(),
  ]);
  state.library = library;
  state.libraryByPath = new Map(library.map((track) => [track.path, track]));
  state.playlists = playlists;
  const select = $('#playlist-source');
  replaceChildren(select, new Option('Shared playlists…', ''));
  playlists.forEach((playlist) => select.add(new Option(`${playlist.name} (${playlist.count})`, playlist.name)));
  const playlistButtons = $('#playlist-buttons');
  replaceChildren(playlistButtons);
  playlists.forEach((playlist) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'secondary';
    button.dataset.playlistName = playlist.name;
    button.setAttribute('aria-pressed', 'false');
    button.textContent = `${playlist.name} (${playlist.count})`;
    button.addEventListener('click', () => selectPlaylist(playlist.name));
    playlistButtons.appendChild(button);
  });
  $('#connection-status').textContent = `${library.length.toLocaleString()} tracks available`;

  const saved = savedState();
  state.shuffle = saved.shuffle !== false;
  state.repeatMode = ['none', 'one', 'all'].includes(saved.repeatMode) ? saved.repeatMode : 'none';
  state.volume = Number.isFinite(saved.volume) ? saved.volume : 1;
  state.leveling = saved.leveling !== false;
  state.visualization = saved.visualization === true;
  $('#shuffle').classList.toggle('active', state.shuffle);
  $('#shuffle').setAttribute('aria-pressed', String(state.shuffle));
  $('#shuffle').setAttribute('aria-label', state.shuffle ? 'Shuffle: on' : 'Shuffle: off');
  updateRepeatControl();
  $('#volume').value = state.volume;
  $('#leveling').checked = state.leveling;
  updateTeslaModeUi(Boolean(saved.tesla));

  const savedSourceName = typeof saved.sourceName === 'string' && saved.sourceName
    ? saved.sourceName
    : (typeof saved.queueSourceName === 'string' && saved.queueSourceName ? saved.queueSourceName : 'All music');
  const knownPlaylist = playlists.some((playlist) => playlist.name === savedSourceName);
  if (savedSourceName !== 'All music' && knownPlaylist) {
    try {
      const result = await tracksForPlaylist(savedSourceName);
      applySourceTracks(savedSourceName, result.tracks, false);
    } catch (_error) {
      applySourceTracks('All music', library, false);
    }
  } else {
    applySourceTracks('All music', library, false);
  }

  const activeTrack = audio.src && state.currentIndex >= 0 ? state.queue[state.currentIndex] : null;
  if (activeTrack) {
    const activeQueuePaths = state.queue.map((track) => track.path);
    state.queue = activeQueuePaths.map((path) => state.libraryByPath.get(path)).filter(Boolean);
    state.currentIndex = state.queue.findIndex((track) => track.path === activeTrack.path);
  } else {
    state.queue = Array.isArray(saved.queue)
      ? saved.queue.map((path) => state.libraryByPath.get(path)).filter(Boolean)
      : [];
    state.queueSourceName = typeof saved.queueSourceName === 'string' ? saved.queueSourceName : '';
    state.currentIndex = state.queue.findIndex((track) => track.path === saved.currentPath);
    state.resumePosition = Math.max(0, Number(saved.position) || 0);
  }
  updatePlayingPlaylist();
  renderQueue();
  setVisualizationEnabled(state.visualization);
  if (state.currentIndex >= 0 && !activeTrack) {
    const track = state.queue[state.currentIndex];
    $('#track-title').textContent = track.title || fileName(track.path);
    $('#track-subtitle').textContent = subtitle(track);
    $('#playback-status').textContent = `Ready to resume at ${formatTime(saved.position || 0)}`;
  }
  setSidePanel(saved.sidePanel);
  if (window.parent !== window && new URLSearchParams(window.location.search).get('embedded') === '1') {
    window.parent.postMessage({ type: 'fluxa-fredplayer-ready' }, fluxaReturnDestination()?.origin || '*');
  }
}

async function initialize() {
  if (launchParameters.get('logout') === '1') {
    try {
      localStorage.removeItem(DEVICE_TOKEN_KEY);
      await fetch('./auth/logout', { method: 'POST', credentials: 'same-origin' });
    } catch (_error) {}
    const destination = fluxaReturnDestination();
    if (destination) {
      destination.searchParams.delete('fred_grant');
      window.location.replace(destination.href);
      return;
    }
  }
  let session = await fetch('./auth/session', { credentials: 'same-origin', cache: 'no-store' });
  let value = await session.json();
  if (value.username && !$('#username').value) $('#username').value = value.username;
  if (!value.authenticated) {
    let token = '';
    try { token = localStorage.getItem(DEVICE_TOKEN_KEY) || ''; } catch (_error) {}
    if (token) {
      session = await fetch('./auth/device', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      });
      value = session.ok ? await session.json() : { authenticated: false };
      if (!value.authenticated) {
        try { localStorage.removeItem(DEVICE_TOKEN_KEY); } catch (_error) {}
      }
    }
  }
  if (!value.authenticated) {
    showLogin();
    return;
  }
  await rememberAuthenticatedDevice();
  if (await completeFluxaAuthorization()) return;
  showPlayer();
  await loadLibrary();
  await playRequestedTrack();
  logDiagnostic('initialized', {
    visibility: document.visibilityState,
    mediaSession: 'mediaSession' in navigator,
    serviceWorker: 'serviceWorker' in navigator,
    serverAudioDsp: true,
  });
}

async function showStartupFailure(error) {
  const message = error && error.message ? error.message : String(error || 'Unknown startup error');
  showPlayer();
  $('#connection-status').textContent = 'FredPlayer could not finish loading';
  $('#playback-status').textContent = message;
  $('#track-list').textContent = `FredPlayer startup error: ${message}`;
  logDiagnostic('startup-failed', { message, stack: error && error.stack ? error.stack : '' });
  try {
    await fetch('./api/diagnostics', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        automatic: true,
        phase: 'startup',
        message,
        stack: error && error.stack ? error.stack : '',
        location: window.location.href,
      }),
    });
  } catch (_error) {}
}

$('#login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('#login-error').textContent = '';
  let authenticated = false;
  try {
    const response = await fetch('./auth/login', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: $('#username').value,
        password: $('#password').value,
        remember: isFluxaTizen() || $('#remember-device').checked,
      }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Sign in failed');
    authenticated = true;
    try {
      if (result.deviceToken) localStorage.setItem(DEVICE_TOKEN_KEY, result.deviceToken);
      else localStorage.removeItem(DEVICE_TOKEN_KEY);
      localStorage.setItem(LOGIN_USERNAME_KEY, $('#username').value);
    } catch (_error) {}
    $('#password').value = '';
    if (await completeFluxaAuthorization()) return;
    showPlayer();
    await loadLibrary();
    await playRequestedTrack();
    logDiagnostic('login-success');
  } catch (error) {
    if (authenticated) await showStartupFailure(error);
    else $('#login-error').textContent = error.message;
  }
});

$('#password-toggle').addEventListener('click', () => {
  const password = $('#password');
  const showing = password.type === 'password';
  password.type = showing ? 'text' : 'password';
  $('#password-field').classList.toggle('showing', showing);
  $('#password-toggle').setAttribute('aria-pressed', String(showing));
  $('#password-toggle').setAttribute('aria-label', showing ? 'Hide password' : 'Show password');
  $('#password-toggle').setAttribute('title', showing ? 'Hide password' : 'Show password');
  password.focus({ preventScroll: true });
});

$('#logout').addEventListener('click', async () => {
  try { localStorage.removeItem(DEVICE_TOKEN_KEY); } catch (_error) {}
  await fetch('./auth/logout', { method: 'POST', credentials: 'same-origin' });
  showLogin();
});
$('#refresh-library').addEventListener('click', loadLibrary);
$('#library-source').addEventListener('click', async () => {
  stopForSourceChange();
  await startSource('All music', state.library);
  logDiagnostic('library-started', { tracks: state.library.length });
});
$('#playlist-source').addEventListener('change', (event) => selectPlaylist(event.target.value));
$('#search').addEventListener('input', () => { state.visibleLimit = 100; renderTracks(); });
$('#shuffle').addEventListener('click', () => {
  state.shuffle = !state.shuffle;
  $('#shuffle').classList.toggle('active', state.shuffle);
  $('#shuffle').setAttribute('aria-pressed', String(state.shuffle));
  $('#shuffle').setAttribute('aria-label', state.shuffle ? 'Shuffle: on' : 'Shuffle: off');
  savePreferences();
});
$('#repeat').addEventListener('click', cycleRepeatMode);
$('#queue-toggle').addEventListener('click', () => showSidePanel('queue'));
$('#lyrics-toggle').addEventListener('click', () => showSidePanel('lyrics'));
$('#clear-queue').addEventListener('click', () => {
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  state.queue = [];
  state.currentIndex = -1;
  state.queueSourceName = '';
  state.streamBaseOffset = 0;
  state.resumePosition = 0;
  state.trackDuration = 0;
  state.seekingPosition = null;
  updatePlayingPlaylist();
  renderQueue();
  saveQueueState();
  savePosition();
});
$('#previous').addEventListener('click', playPrevious);
$('#next').addEventListener('click', playNext);
$('#stop').addEventListener('click', () => {
  stopPlayback();
});
$('#play').addEventListener('click', async () => {
  if (state.queueSourceName !== state.sourceName && state.displayedTracks.length) {
    state.queue = state.shuffle ? shuffled(state.displayedTracks) : [...state.displayedTracks];
    state.queueSourceName = state.sourceName;
    await playTrack(0);
    return;
  }
  if (!audio.src) {
    const saved = savedState();
    if (state.currentIndex >= 0) await playTrack(state.currentIndex, saved.position || 0);
    else if (state.queue.length) await playTrack(0);
    else if (state.displayedTracks.length) {
      state.queue = state.shuffle ? shuffled(state.displayedTracks) : [...state.displayedTracks];
      state.queueSourceName = state.sourceName;
      await playTrack(0);
    } else {
      $('#playback-status').textContent = 'Choose a library or shared playlist first.';
    }
    return;
  }
  if (audio.paused) {
    try { await audio.play(); } catch (error) { logDiagnostic('resume-rejected', { message: error.message }); }
  } else audio.pause();
});
$('#volume').addEventListener('input', (event) => { state.volume = Number(event.target.value); applyVolume(); savePreferences(); });
$('#leveling').addEventListener('change', async (event) => {
  const position = playbackPosition();
  const wasPlaying = !audio.paused;
  state.leveling = event.target.checked;
  savePreferences();
  if (state.currentIndex >= 0 && audio.src) {
    await playTrack(state.currentIndex, position, wasPlaying);
  }
});
$('#visualization-toggle').addEventListener('click', () => setVisualizationEnabled(!state.visualization));
$('#seek').addEventListener('input', (event) => {
  state.seekingPosition = Number(event.target.value);
  $('#elapsed').textContent = formatTime(state.seekingPosition);
  drawVisualization();
  updateLyricsHighlight();
});
$('#seek').addEventListener('change', async (event) => {
  const position = Number(event.target.value);
  const wasPlaying = !audio.paused;
  state.seekingPosition = null;
  if (state.currentIndex >= 0) await playTrack(state.currentIndex, position, wasPlaying);
});
$('#tesla-mode').addEventListener('click', () => {
  const enabled = !teslaModeEnabled();
  updateTeslaModeUi(enabled);
  logDiagnostic('tesla-mode', { enabled });
  if (enabled) {
    logDiagnostic('tesla-control-logging-enabled', {
      mediaSession: 'mediaSession' in navigator,
      registeredMediaActions: state.mediaSessionActions,
      rawEvents: ['keydown', 'keyup', 'wheel'],
    });
  }
  savePreferences();
});
$('#save-diagnostics').addEventListener('click', async () => {
  const report = {
    events: state.diagnostics,
    visibility: document.visibilityState,
    audio: {
      paused: audio.paused,
      ended: audio.ended,
      currentTime: playbackPosition(),
      duration: state.trackDuration,
      readyState: audio.readyState,
      networkState: audio.networkState,
      volume: audio.volume,
    },
    capabilities: {
      mp3: audio.canPlayType('audio/mpeg'),
      flac: audio.canPlayType('audio/flac'),
      opus: audio.canPlayType('audio/ogg; codecs="opus"'),
      mediaSession: 'mediaSession' in navigator,
      serviceWorker: 'serviceWorker' in navigator,
    },
  };
  try {
    await api('diagnostics', { method: 'POST', body: JSON.stringify(report) });
    logDiagnostic('report-saved');
  } catch (error) { logDiagnostic('report-failed', { message: error.message }); }
});
$('#clear-diagnostics').addEventListener('click', () => { state.diagnostics = []; $('#diagnostics').textContent = ''; });

audio.addEventListener('loadedmetadata', () => { $('#seek').max = state.trackDuration || 1; });
audio.addEventListener('timeupdate', () => {
  const position = playbackPosition();
  if (!Number.isFinite(state.seekingPosition)) $('#seek').value = position;
  $('#elapsed').textContent = formatTime(position);
  $('#duration').textContent = formatTime(state.trackDuration);
  updateLyricsHighlight();
  if (audio.paused) drawVisualization();
});
audio.addEventListener('play', () => { $('#play').classList.remove('play-required'); updateTransport(); startVisualizationLoop(); syncLyricsTimer(); $('#playback-status').textContent = 'Playing'; logDiagnostic('audio-play'); });
audio.addEventListener('pause', () => { stopVisualizationLoop(); stopLyricsTimer(); drawVisualization(); updateTransport(); if (!audio.ended && audio.src) $('#playback-status').textContent = 'Paused'; logDiagnostic('audio-pause', { position: playbackPosition() }); savePosition(); });
audio.addEventListener('playing', () => { $('#playback-status').textContent = 'Playing'; logDiagnostic('audio-playing', { visibility: document.visibilityState }); });
audio.addEventListener('waiting', () => { $('#playback-status').textContent = 'Buffering…'; logDiagnostic('audio-waiting', { position: playbackPosition() }); });
audio.addEventListener('stalled', () => logDiagnostic('audio-stalled', { position: playbackPosition() }));
audio.addEventListener('error', () => {
  const message = audio.error?.message || `Media error ${audio.error?.code || 'unknown'}`;
  $('#playback-status').textContent = message;
  logDiagnostic('audio-error', { code: audio.error?.code, message });
});
audio.addEventListener('ended', () => { logDiagnostic('audio-ended', { visibility: document.visibilityState, repeatMode: state.repeatMode }); handleTrackEnded(); });
document.addEventListener('visibilitychange', () => logDiagnostic('visibility-change', {
  visibility: document.visibilityState,
  paused: audio.paused,
  position: playbackPosition(),
}));
document.addEventListener('keydown', (event) => logTeslaControl('dom-keyboard', 'keydown', {
  key: event.key,
  code: event.code,
  repeat: event.repeat,
  location: event.location,
  altKey: event.altKey,
  ctrlKey: event.ctrlKey,
  metaKey: event.metaKey,
  shiftKey: event.shiftKey,
}), true);
document.addEventListener('keyup', (event) => logTeslaControl('dom-keyboard', 'keyup', {
  key: event.key,
  code: event.code,
  repeat: event.repeat,
  location: event.location,
  altKey: event.altKey,
  ctrlKey: event.ctrlKey,
  metaKey: event.metaKey,
  shiftKey: event.shiftKey,
}), true);
window.addEventListener('wheel', (event) => logTeslaControl('dom-wheel', 'wheel', {
  deltaX: event.deltaX,
  deltaY: event.deltaY,
  deltaZ: event.deltaZ,
  deltaMode: event.deltaMode,
  altKey: event.altKey,
  ctrlKey: event.ctrlKey,
  metaKey: event.metaKey,
  shiftKey: event.shiftKey,
}), { capture: true, passive: true });
window.addEventListener('online', () => { $('#connection-status').textContent = `${state.library.length.toLocaleString()} tracks available`; logDiagnostic('network-online'); });
window.addEventListener('offline', () => { $('#connection-status').textContent = 'Network offline'; logDiagnostic('network-offline'); });
window.addEventListener('beforeunload', savePosition);
setInterval(() => { if (!audio.paused) savePosition(); }, 5000);

if ('mediaSession' in navigator) {
  const registerMediaAction = (action, handler) => {
    try {
      navigator.mediaSession.setActionHandler(action, (details = {}) => {
        logTeslaControl('media-session', action, {
          reportedAction: details.action || action,
          seekOffset: Number.isFinite(details.seekOffset) ? details.seekOffset : null,
          seekTime: Number.isFinite(details.seekTime) ? details.seekTime : null,
          fastSeek: details.fastSeek === true,
        });
        try {
          const result = handler(details);
          if (result?.catch) result.catch((error) => logTeslaControl('media-session', `${action}-failed`, {
            message: error.message,
          }));
        } catch (error) {
          logTeslaControl('media-session', `${action}-failed`, { message: error.message });
        }
      });
      state.mediaSessionActions.push(action);
    } catch (_error) {}
  };
  const seekRelative = (offset) => {
    if (state.currentIndex < 0) return;
    const position = Math.max(0, Math.min(state.trackDuration || Infinity, playbackPosition() + offset));
    playTrack(state.currentIndex, position, !audio.paused);
  };
  registerMediaAction('play', () => audio.play());
  registerMediaAction('pause', () => audio.pause());
  registerMediaAction('stop', stopPlayback);
  registerMediaAction('previoustrack', playPrevious);
  registerMediaAction('nexttrack', playNext);
  registerMediaAction('seekbackward', (details) => seekRelative(-(details.seekOffset || 10)));
  registerMediaAction('seekforward', (details) => seekRelative(details.seekOffset || 10));
  registerMediaAction('seekto', (details) => {
    if (Number.isFinite(details.seekTime) && state.currentIndex >= 0) {
      playTrack(state.currentIndex, details.seekTime, !audio.paused);
    }
  });
}
configureFluxaReturn();
installTizenLoginKeyboard();
installTizenRemoteNavigation();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('./service-worker.js').catch(() => {});

initialize().catch(showStartupFailure);
