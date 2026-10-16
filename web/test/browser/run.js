import { createAlphaVideoGpuPlayer } from '../../dist/index.mjs';

const params = new URLSearchParams(location.search);
const cardsDir = params.get('cards') ?? '../../../tests/fixtures';
const only = params.get('only') ? new RegExp(params.get('only')) : null;
const CARDS = ['card_256x256', 'card_383x217'];
const FIXTURES = ['astc4x4.ktx2', 'astc4x4_stream.ktx2', 'astc4x4_nozstd.ktx2', 'astc6x6.ktx2', 'astc8x8.ktx2'];
const MODES = [
  { stream: true, memoryStrategy: 'vram' },
  { stream: true, memoryStrategy: 'ram' },
  { stream: false, memoryStrategy: 'vram' },
  { stream: false, memoryStrategy: 'ram' },
];
const SEEKS = [0, 1, 29, 30, 31, 37, 59];
const PLAYBACK_MS = 3000;

const logEl = document.getElementById('log');
const log = (text, cls) => {
  const line = document.createElement('div');
  line.textContent = text;
  if (cls) line.className = cls;
  logEl.append(line);
};
const nextFrame = () => new Promise((resolve) => requestAnimationFrame(resolve));

/** The frame index the canvas shows, read from the colour, inverted and alpha code cells; null if they disagree. */
function readFrame(canvas, layout) {
  const copy = document.createElement('canvas');
  copy.width = canvas.width;
  copy.height = canvas.height;
  const ctx = copy.getContext('2d');
  ctx.drawImage(canvas, 0, 0);
  const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const mean = (r, channel) => {
    const inset = Math.floor(r.w / 4);
    let sum = 0, n = 0;
    for (let y = r.y + inset; y < r.y + r.h - inset; y++) {
      for (let x = r.x + inset; x < r.x + r.w - inset; x++) { sum += data[(y * canvas.width + x) * 4 + channel]; n++; }
    }
    return sum / n;
  };
  const code = (cells, channel, inverted) => cells.reduce((c, cell) => (c << 1) | ((mean(cell, channel) > 127) !== inverted ? 1 : 0), 0);
  const colour = code(layout.codeCells, 0, false);
  const agree = colour === code(layout.invertedCells, 0, true) && colour === code(layout.alphaCodeCells, 3, false);
  return agree ? colour : null;
}

async function runCase(card, fixture, mode, layout) {
  const canvas = document.createElement('canvas');
  canvas.width = layout.width;
  canvas.height = layout.height;
  document.body.append(canvas);
  const failures = [];
  let error = null;
  const player = createAlphaVideoGpuPlayer(canvas, { autoPlay: false, loop: true, ...mode, onError: (e) => { error = e; } });
  try {
    await player.setSource(`${cardsDir}/${card}/${fixture}?${Date.now()}`);
    if (error) throw error;

    for (const frame of SEEKS) {
      player.seekToFrame(frame);
      let seen = null;
      for (let t = performance.now(); performance.now() - t < 2000; await nextFrame()) {
        seen = readFrame(canvas, layout);
        if (seen === frame) break;
      }
      if (seen !== frame) failures.push(`seek ${frame}: showed ${seen}`);
    }

    player.seekToFrame(0);
    player.play();
    const codes = [];
    for (let t = performance.now(); performance.now() - t < PLAYBACK_MS; await nextFrame()) codes.push(readFrame(canvas, layout));
    const frames = layout.frames;
    let wraps = 0;
    for (let i = 1; i < codes.length; i++) {
      const [a, b] = [codes[i - 1], codes[i]];
      if (b === null) { failures.push(`playback: unreadable frame after ${a}`); break; }
      if (a === null || a === b) continue;
      if ((b - a + frames) % frames >= frames / 2) { failures.push(`playback: went back from ${a} to ${b}`); break; }
      if (b < a) wraps++;
    }
    if (wraps === 0) {
      const seen = codes.filter((c, i) => c !== codes[i - 1]);
      failures.push(`playback: never looped in ${PLAYBACK_MS / 1000} s (showed ${seen.join(' ')})`);
    }
  } catch (e) {
    failures.push(`error: ${e.code ? `${e.code}: ` : ''}${e.message}`);
  } finally {
    player.destroy();
    canvas.remove();
  }
  return failures;
}

async function main() {
  const astc = (() => {
    const gl = document.createElement('canvas').getContext('webgl2');
    return !!gl?.getExtension('WEBGL_compressed_texture_astc');
  })();
  log(`GPU ASTC ${astc ? 'available' : 'unavailable: software decoding'}`);
  const results = [];
  for (const card of CARDS) {
    const layout = await (await fetch(`${cardsDir}/${card}/layout.json`)).json();
    for (const fixture of FIXTURES) {
      if (!(await fetch(`${cardsDir}/${card}/${fixture}`, { method: 'HEAD' })).ok) continue;
      for (const mode of MODES) {
        const name = `${card}/${fixture} ${mode.stream ? 'stream' : 'preload'} ${mode.memoryStrategy}`;
        if (only && !only.test(name)) continue;
        const failures = await runCase(card, fixture, mode, layout);
        results.push({ name, failures });
        log(`${failures.length ? 'FAIL' : 'PASS'} ${name}${failures.length ? `: ${failures.join('; ')}` : ''}`, failures.length ? 'fail' : 'pass');
      }
    }
  }
  const failed = results.filter((r) => r.failures.length).length;
  log(`${results.length - failed}/${results.length} cases pass`, failed ? 'fail' : 'pass');
  window.results = { astc, results };
  window.done = true;
}

main().catch((e) => {
  log(`error: ${e.message}`, 'fail');
  window.results = { error: e.message };
  window.done = true;
});
