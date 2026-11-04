// The card renderer. Card JSON arrives already checked (CardSpec.java). Everything is put
// on the page with textContent or SVG attributes: card content is never parsed as HTML.
(function () {
  'use strict';
  var root = document.getElementById('card');
  var PALETTE = ['#00d4ff', '#a78bfa', '#2dd4bf', '#f472b6', '#60a5fa', '#d6c7a1'];
  var SVGNS = 'http://www.w3.org/2000/svg';

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function svg(tag, attrs, text) {
    var e = document.createElementNS(SVGNS, tag);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (text != null) e.textContent = text;
    return e;
  }

  // **bold** inside text cards, as text nodes only.
  function richLine(parent, line) {
    var parts = String(line).split(/(\*\*[^*]+\*\*)/);
    parts.forEach(function (p) {
      if (/^\*\*[^*]+\*\*$/.test(p)) parent.appendChild(el('strong', null, p.slice(2, -2)));
      else if (p) parent.appendChild(document.createTextNode(p));
    });
  }

  function text(c, box) {
    String(c.body).split(/\n{2,}/).forEach(function (para) {
      var lines = para.split('\n');
      var bullets = lines.length > 1 && lines.every(function (l) { return /^\s*([-*•]|\d+[.)])\s+/.test(l); });
      if (bullets) {
        var ul = el(/^\s*\d/.test(lines[0]) ? 'ol' : 'ul');
        lines.forEach(function (l) { var li = el('li'); richLine(li, l.replace(/^\s*([-*•]|\d+[.)])\s+/, '')); ul.appendChild(li); });
        box.appendChild(ul);
      } else {
        var p = el('p');
        lines.forEach(function (l, i) { if (i) p.appendChild(el('br')); richLine(p, l.replace(/^#+\s*/, '')); });
        box.appendChild(p);
      }
    });
  }

  function list(c, box) {
    var l = el(c.ordered ? 'ol' : 'ul');
    c.items.forEach(function (it) { var li = el('li'); richLine(li, it); l.appendChild(li); });
    box.appendChild(l);
  }

  function table(c, box) {
    var t = el('table');
    if (c.columns && c.columns.length) {
      var tr = el('tr');
      c.columns.forEach(function (h) { tr.appendChild(el('th', null, h)); });
      var thead = el('thead'); thead.appendChild(tr); t.appendChild(thead);
    }
    var tb = el('tbody');
    c.rows.forEach(function (r) {
      var tr = el('tr');
      r.forEach(function (v) { tr.appendChild(el('td', null, v)); });
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    box.appendChild(t);
  }

  function image(c, box) {
    var f = el('figure');
    var img = el('img');
    img.alt = c.caption || c.title || '';
    img.src = c.gen ? '/gen?id=' + encodeURIComponent(c.gen) : '/img?u=' + encodeURIComponent(c.url);
    img.onerror = function () { f.replaceChild(el('p', 'err', 'That picture could not be loaded.'), img); };
    f.appendChild(img);
    if (c.caption) f.appendChild(el('figcaption', null, c.caption));
    box.appendChild(f);
  }

  function chart(c, box) {
    var W = 1600, H = 640, padL = 110, padB = 90, padT = 30, padR = 30;
    var s = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, width: '100%', role: 'img', 'aria-label': c.title || 'chart' });
    var series = c.series, labels = c.labels || [];
    if (c.kind === 'pie') {
      var vals = series[0].values, total = vals.reduce(function (a, b) { return a + Math.max(0, b); }, 0) || 1;
      var cx = W / 2 - 300, cy = H / 2, r = 280, a0 = -Math.PI / 2;
      vals.forEach(function (v, i) {
        var a1 = a0 + (Math.max(0, v) / total) * Math.PI * 2;
        var large = a1 - a0 > Math.PI ? 1 : 0;
        var d = 'M' + cx + ',' + cy + ' L' + (cx + r * Math.cos(a0)) + ',' + (cy + r * Math.sin(a0)) +
                ' A' + r + ',' + r + ' 0 ' + large + ' 1 ' + (cx + r * Math.cos(a1)) + ',' + (cy + r * Math.sin(a1)) + ' Z';
        if (vals.length === 1) d = 'M' + (cx - r) + ',' + cy + ' a' + r + ',' + r + ' 0 1,0 ' + (2 * r) + ',0 a' + r + ',' + r + ' 0 1,0 ' + (-2 * r) + ',0';
        s.appendChild(svg('path', { d: d, fill: PALETTE[i % PALETTE.length], stroke: '#05060c', 'stroke-width': 4 }));
        var ly = 80 + i * 56;
        s.appendChild(svg('rect', { x: W / 2 + 60, y: ly - 28, width: 34, height: 34, rx: 6, fill: PALETTE[i % PALETTE.length] }));
        s.appendChild(svg('text', { x: W / 2 + 112, y: ly, 'font-size': 34 }, (labels[i] || '') + '  ' + fmt(v) + ' (' + Math.round(v / total * 100) + '%)'));
        a0 = a1;
      });
      box.appendChild(s);
      return;
    }
    var n = Math.max.apply(null, series.map(function (x) { return x.values.length; }));
    var max = Math.max.apply(null, series.map(function (x) { return Math.max.apply(null, x.values); }));
    var min = Math.min(0, Math.min.apply(null, series.map(function (x) { return Math.min.apply(null, x.values); })));
    if (max === min) max = min + 1;
    // Round the axis to a readable step (1, 2 or 5 times a power of ten).
    var raw = (max - min) / 4, pow = Math.pow(10, Math.floor(Math.log10(raw))), nice = [1, 2, 5, 10].map(function (m) { return m * pow; }).find(function (v) { return v >= raw; });
    max = Math.ceil(max / nice) * nice; min = Math.floor(min / nice) * nice;
    var plotW = W - padL - padR, plotH = H - padT - padB;
    var y = function (v) { return padT + plotH - (v - min) / (max - min) * plotH; };
    for (var g = 0; g <= 4; g++) {
      var gv = min + (max - min) * g / 4, gy = y(gv);
      s.appendChild(svg('line', { x1: padL, x2: W - padR, y1: gy, y2: gy, stroke: 'rgba(255,255,255,0.12)', 'stroke-width': 2 }));
      s.appendChild(svg('text', { x: padL - 16, y: gy + 10, 'text-anchor': 'end', 'font-size': 28 }, fmt(gv)));
    }
    var step = plotW / Math.max(1, n);
    for (var i = 0; i < n; i++) {
      if (labels[i] != null && (n <= 12 || i % Math.ceil(n / 12) === 0))
        s.appendChild(svg('text', { x: padL + step * (i + 0.5), y: H - padB + 46, 'text-anchor': 'middle', 'font-size': 28 }, String(labels[i]).slice(0, 14)));
    }
    series.forEach(function (ser, si) {
      var color = PALETTE[si % PALETTE.length];
      if (c.kind === 'line') {
        var pts = ser.values.map(function (v, i) { return (padL + step * (i + 0.5)) + ',' + y(v); }).join(' ');
        s.appendChild(svg('polyline', { points: pts, fill: 'none', stroke: color, 'stroke-width': 6, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
        ser.values.forEach(function (v, i) { s.appendChild(svg('circle', { cx: padL + step * (i + 0.5), cy: y(v), r: 8, fill: color })); });
      } else {
        var bw = step * 0.8 / series.length;
        ser.values.forEach(function (v, i) {
          var x = padL + step * i + step * 0.1 + bw * si, top = y(Math.max(v, 0)), bot = y(Math.min(v, 0));
          s.appendChild(svg('rect', { x: x, y: top, width: Math.max(2, bw - 6), height: Math.max(2, bot - top), rx: 6, fill: color }));
        });
      }
    });
    box.appendChild(s);
    if (series.length > 1 || series[0].name) {
      var lg = el('div', 'legend');
      series.forEach(function (ser, si) { var sp = el('span', null, ser.name || ('Series ' + (si + 1))); sp.style.setProperty('--c', PALETTE[si % PALETTE.length]); lg.appendChild(sp); });
      box.appendChild(lg);
    }
  }

  function fmt(v) {
    var a = Math.abs(v);
    if (a >= 1e6) return (v / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
    if (a >= 1e4) return (v / 1e3).toFixed(1).replace(/\.0$/, '') + 'k';
    return Math.round(v * 100) / 100 + '';
  }

  var RENDER = { text: text, list: list, table: table, image: image, chart: chart };

  window.FridayCards = {
    show: function (json) {
      var c;
      try { c = JSON.parse(json); } catch (e) { return false; }
      root.textContent = '';
      root.scrollTop = 0;
      // The app's look: the accent, the Motion level (full, calm, off) and who answered.
      var look = c._look || {};
      var docEl = document.documentElement;
      if (/^#[0-9a-f]{6}$/i.test(look.accent || '')) docEl.style.setProperty('--fr-accent', look.accent);
      docEl.setAttribute('data-motion', ({ FULL: 'full', CALM: 'calm', OFF: 'off' })[look.motion] || 'full');
      var who = String(look.who || 'Claude').slice(0, 40).toUpperCase();
      docEl.style.setProperty('--fr-who', JSON.stringify(who));
      root.classList.remove('in'); void root.offsetWidth; root.classList.add('in');
      if (c.title) root.appendChild(el('h1', null, c.title));
      var box = el('section', 'glass');
      try { (RENDER[c.type] || text)(c, box); }
      catch (e) { box.textContent = ''; box.appendChild(el('p', 'err', 'This card could not be drawn.')); }
      root.appendChild(box);
      return true;
    },
    scroll: function (dir) { root.scrollBy(0, dir * root.clientHeight * 0.6); }
  };
})();
