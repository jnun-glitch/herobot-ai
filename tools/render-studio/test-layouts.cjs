const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');

const html = readFileSync(join(__dirname, 'index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'Render Studio script exists');

class FakeElement {
  constructor(id = '') {
    this.id = id;
    this.value = ({ width: '2560', height: '1365', scale: '3' })[id] ?? '';
    this.dataset = {};
    this.textContent = '';
    this.children = [];
    this.listeners = {};
    this.className = '';
    this.classList = { toggle() {} };
  }
  addEventListener(type, callback) { this.listeners[type] = callback; }
  append(child) { this.children.push(child); }
  replaceChildren(...children) { this.children = children; }
  click() { this.listeners.click?.(); }
}

const drawContext = {
  font: '12px monospace',
  fillRect() {},
  setTransform() {},
  fillText() {},
  measureText(value) {
    const size = Number(this.font.match(/([\d.]+)px/)?.[1] ?? 12);
    return { width: String(value).length * size * 0.62 };
  }
};
const elements = new Map();
const getElement = id => {
  if (!elements.has(id)) elements.set(id, new FakeElement(id));
  return elements.get(id);
};
const canvas = getElement('preview');
canvas.getContext = () => drawContext;
canvas.toBlob = callback => { canvas.exportedMime = 'image/png'; callback({ type: 'image/png' }); };

const pageButtons = ['home', 'kits', 'confirm'].map(page => {
  const button = new FakeElement();
  button.dataset.page = page;
  return button;
});
const document = {
  getElementById: getElement,
  querySelectorAll: selector => selector === '[data-page]' ? pageButtons : [],
  createElement: tag => new FakeElement(tag)
};
const window = { addEventListener() {}, HeroBotRenderStudio: null };
let exportedLink;
const URL = {
  createObjectURL: () => 'blob:render-studio-test',
  revokeObjectURL() {}
};

vm.runInNewContext(script, {
  document, window, URL, console,
  setTimeout() {},
  Math, Number, String, Object, Array, Infinity
});

assert.equal(getElement('resultTitle').textContent, 'Layout-Prüfung bestanden');
assert.equal(getElement('resultSummary').textContent, '18/18 Ansichten OK');
assert.equal(getElement('resultList').children.length, 18);

const studio = window.HeroBotRenderStudio;
assert.ok(studio, 'Layout checker is exposed to the preview app');
const sizes = [
  [320, 240, 1], [640, 360, 1], [854, 480, 1],
  [1280, 720, 2], [1920, 1080, 2], [2560, 1365, 3],
  [1280, 720, 3], [1920, 480, 2], [3840, 2160, 4]
];
for (const [width, height, scale] of sizes) {
  for (const page of ['home', 'kits', 'confirm']) {
    const issues = studio.checkLayout(page, width, height, scale);
    assert.deepEqual(Array.from(issues), [], `${page} ${width}x${height} GUI ${scale}x`);
  }
}

pageButtons.find(button => button.dataset.page === 'kits').click();
assert.equal(getElement('renderTitle').textContent, 'KIT-AUSWAHL');
pageButtons.find(button => button.dataset.page === 'confirm').click();
assert.equal(getElement('renderTitle').textContent, 'BESTÄTIGUNG');

const originalCreateElement = document.createElement;
document.createElement = tag => {
  exportedLink = originalCreateElement(tag);
  exportedLink.click = () => { exportedLink.wasClicked = true; };
  return exportedLink;
};
getElement('renderButton').click();
assert.equal(canvas.exportedMime, 'image/png');
assert.equal(exportedLink.download, 'herobot-confirm-2560x1365.png');
assert.equal(exportedLink.wasClicked, true);

console.log(`Render Studio OK: ${sizes.length * 3} Größenchecks, 3 Seiten, PNG-Export.`);
