// Small dependency-free SVG charts for the dashboard: lines (with crosshair tooltip) and bars (with per-bar tooltip).
'use strict';
(function () {
  const NS = 'http://www.w3.org/2000/svg';
  const tip = () => document.getElementById('chart-tip');

  function css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }
  function el(tag, attrs, parent) {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(e);
    return e;
  }
  function esc(s) {
    return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }
  function fmtNum(v) {
    if (v == null || !isFinite(v)) return '–';
    const a = Math.abs(v);
    if (a >= 1e9) return (v / 1e9).toFixed(a >= 1e10 ? 0 : 1) + 'b';
    if (a >= 1e6) return (v / 1e6).toFixed(a >= 1e7 ? 0 : 1) + 'm';
    if (a >= 1e4) return (v / 1e3).toFixed(a >= 1e5 ? 0 : 1) + 'k';
    if (Number.isInteger(v)) return v.toLocaleString();
    return v.toFixed(a < 10 ? 2 : 1);
  }
  function niceTicks(min, max, n) {
    if (max <= min) max = min + 1;
    const raw = (max - min) / n;
    const mag = Math.pow(10, Math.floor(Math.log10(raw)));
    const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw) || raw;
    const lo = Math.floor(min / step) * step;
    const hi = Math.ceil(max / step) * step;
    const out = [];
    for (let v = lo; v <= hi + step / 2; v += step) out.push(+v.toFixed(10));
    return out;
  }
  function showTip(html, ev) {
    const t = tip();
    t.innerHTML = html;
    t.hidden = false;
    const w = t.offsetWidth, h = t.offsetHeight;
    let x = ev.clientX + 14, y = ev.clientY + 14;
    if (x + w > innerWidth - 8) x = ev.clientX - w - 14;
    if (y + h > innerHeight - 8) y = ev.clientY - h - 14;
    t.style.left = Math.max(8, x) + 'px';
    t.style.top = Math.max(8, y) + 'px';
  }
  function hideTip() { tip().hidden = true; }
  function nodata(host, msg) {
    host.innerHTML = '<div class="nodata">' + esc(msg || 'No data yet') + '</div>';
  }
  function legend(host, series) {
    if (series.length < 2) return;
    const row = document.createElement('div');
    row.className = 'legend-row';
    row.innerHTML = series.map(s => '<span><i class="' + (s.dash ? 'dash' : '') + '" style="background:' + s.color +
      ';border-color:' + s.color + '"></i>' + esc(s.name) + '</span>').join('');
    host.appendChild(row);
  }
  function timeLabel(x, kind) {
    const d = new Date(x);
    if (kind === 'day') return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }

  // series: [{name, color, points: [[x, y]], dash, step}]; opts.x: 'time' | 'day' | 'num'
  function line(host, opts) {
    host.innerHTML = '';
    const series = opts.series.filter(s => s.points && s.points.length);
    if (!series.length) return nodata(host, opts.empty);
    legend(host, series);
    const W = host.clientWidth || 400;
    const H = host.clientHeight - (series.length > 1 ? 22 : 0) || 200;
    const m = { l: 44, r: 12, t: 8, b: 22 };
    const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'chart' }, host);
    let xmin = Infinity, xmax = -Infinity, ymin = opts.yMin0 === false ? Infinity : 0, ymax = -Infinity;
    for (const s of series) for (const [x, y] of s.points) {
      if (x < xmin) xmin = x; if (x > xmax) xmax = x;
      if (y != null && isFinite(y)) { if (y < ymin) ymin = y; if (y > ymax) ymax = y; }
    }
    if (ymax === -Infinity) ymax = 1;
    if (opts.x === 'day' && xmax - xmin < 2 * 86400e3) { xmin -= 86400e3; xmax += 86400e3; }
    if (xmax === xmin) { xmin -= 1; xmax += 1; }
    const yt = niceTicks(ymin, ymax * 1.05 || 1, 4);
    const y0 = yt[0], y1 = yt[yt.length - 1];
    const sx = x => m.l + (x - xmin) / (xmax - xmin) * (W - m.l - m.r);
    const sy = y => H - m.b - (y - y0) / (y1 - y0) * (H - m.t - m.b);
    const g = el('g', { class: 'grid' }, svg), ax = el('g', { class: 'axis' }, svg);
    for (const v of yt) {
      el('line', { x1: m.l, x2: W - m.r, y1: sy(v), y2: sy(v) }, g);
      el('text', { x: m.l - 6, y: sy(v) + 4, 'text-anchor': 'end' }, ax).textContent = (opts.yFmt || fmtNum)(v);
    }
    const nx = Math.max(2, Math.min(6, Math.floor((W - m.l) / 90)));
    let lastLabel = null;
    for (let i = 0; i <= nx; i++) {
      const x = xmin + (xmax - xmin) * i / nx;
      const label = opts.x === 'num' ? fmtNum(x) : timeLabel(x, opts.x);
      if (label === lastLabel) continue;
      lastLabel = label;
      el('text', { x: sx(x), y: H - 6, 'text-anchor': i === 0 ? 'start' : i === nx ? 'end' : 'middle' }, ax).textContent = label;
    }
    for (const s of series) {
      let d = '', pen = false;
      for (const [x, y] of s.points) {
        if (y == null || !isFinite(y)) { pen = false; continue; }
        d += (pen ? 'L' : 'M') + sx(x).toFixed(1) + ' ' + sy(y).toFixed(1);
        pen = true;
      }
      el('path', { d, fill: 'none', stroke: s.color, 'stroke-width': 2, 'stroke-linejoin': 'round',
        'stroke-dasharray': s.dash ? '5 4' : 'none' }, svg);
      if (s.points.length === 1) {
        const [x, y] = s.points[0];
        el('circle', { cx: sx(x), cy: sy(y), r: 4, fill: s.color }, svg);
      }
    }
    // hover: crosshair + values of every series at the nearest x
    const cross = el('line', { y1: m.t, y2: H - m.b, stroke: css('--muted'), 'stroke-width': 1, visibility: 'hidden' }, svg);
    const dots = series.map(s => el('circle', { r: 4, fill: s.color, stroke: css('--surface'), 'stroke-width': 2, visibility: 'hidden' }, svg));
    const hit = el('rect', { x: m.l, y: m.t, width: W - m.l - m.r, height: H - m.t - m.b, fill: 'transparent' }, svg);
    hit.addEventListener('mousemove', ev => {
      const r = svg.getBoundingClientRect();
      const px = (ev.clientX - r.left) * W / r.width;
      const x = xmin + (px - m.l) / (W - m.l - m.r) * (xmax - xmin);
      let rows = '', at = null;
      series.forEach((s, i) => {
        let best = null;
        for (const p of s.points) if (best == null || Math.abs(p[0] - x) < Math.abs(best[0] - x)) best = p;
        if (!best || best[1] == null) { dots[i].setAttribute('visibility', 'hidden'); return; }
        at = at == null ? best[0] : at;
        dots[i].setAttribute('cx', sx(best[0])); dots[i].setAttribute('cy', sy(best[1]));
        dots[i].setAttribute('visibility', 'visible');
        rows += '<div class="row"><span><i style="background:' + s.color + '"></i>' + esc(s.name) + '</span><b>' +
          (opts.yFmt || fmtNum)(best[1]) + '</b></div>';
      });
      if (at == null) return;
      cross.setAttribute('x1', sx(at)); cross.setAttribute('x2', sx(at)); cross.setAttribute('visibility', 'visible');
      const head = opts.x === 'num' ? fmtNum(at) : new Date(at).toLocaleString(undefined,
        opts.x === 'day' ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
      showTip('<b>' + esc(head) + '</b>' + rows, ev);
    });
    hit.addEventListener('mouseleave', () => {
      hideTip(); cross.setAttribute('visibility', 'hidden'); dots.forEach(d => d.setAttribute('visibility', 'hidden'));
    });
  }

  // rows: [[label, value, extraTooltip?]]; opts.horizontal draws one bar per line
  function bars(host, opts) {
    host.innerHTML = '';
    const rows = opts.rows || [];
    if (!rows.length || rows.every(r => !r[1])) return nodata(host, opts.empty);
    const W = host.clientWidth || 400, H = host.clientHeight || 200;
    const svg = el('svg', { viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': opts.label || 'chart' }, host);
    const max = Math.max(...rows.map(r => r[1] || 0)) || 1;
    const color = opts.color || css('--s1');
    const fmt = opts.fmt || fmtNum;
    const ax = el('g', { class: 'axis' }, svg);
    const bind = (rect, r) => {
      rect.addEventListener('mousemove', ev => showTip('<b>' + esc(r[0]) + '</b><div class="row"><span>' +
        esc(opts.valueName || 'count') + '</span><b>' + fmt(r[1]) + '</b></div>' + (r[2] ? '<div>' + esc(r[2]) + '</div>' : ''), ev));
      rect.addEventListener('mouseleave', hideTip);
    };
    if (opts.horizontal) {
      const labelW = Math.min(150, W * 0.38), valueW = 54;
      const bh = Math.max(8, Math.min(22, (H - 4) / rows.length - 4));
      rows.forEach((r, i) => {
        const y = 2 + i * (bh + 4);
        el('text', { x: labelW - 6, y: y + bh / 2 + 4, 'text-anchor': 'end' }, ax).textContent =
          String(r[0]).length > 22 ? String(r[0]).slice(0, 21) + '…' : r[0];
        const w = Math.max(r[1] ? 2 : 0, (r[1] || 0) / max * (W - labelW - valueW));
        const rect = el('rect', { x: labelW, y, width: w, height: bh, rx: 4, fill: color }, svg);
        el('text', { x: labelW + w + 6, y: y + bh / 2 + 4 }, ax).textContent = fmt(r[1]);
        const hit = el('rect', { x: 0, y: y - 2, width: W, height: bh + 4, fill: 'transparent' }, svg);
        bind(hit, r);
        void rect;
      });
      return;
    }
    const m = { l: 40, r: 8, t: 8, b: 22 };
    const yt = niceTicks(0, max, 4);
    const top = yt[yt.length - 1];
    const sy = v => H - m.b - v / top * (H - m.t - m.b);
    const g = el('g', { class: 'grid' }, svg);
    for (const v of yt) {
      el('line', { x1: m.l, x2: W - m.r, y1: sy(v), y2: sy(v) }, g);
      el('text', { x: m.l - 6, y: sy(v) + 4, 'text-anchor': 'end' }, ax).textContent = fmt(v);
    }
    const slot = (W - m.l - m.r) / rows.length;
    const bw = Math.max(3, Math.min(48, slot - 2));
    const every = Math.ceil(rows.length / Math.max(1, Math.floor((W - m.l) / 46)));
    rows.forEach((r, i) => {
      const x = m.l + i * slot + (slot - bw) / 2;
      const h = Math.max(r[1] ? 2 : 0, H - m.b - sy(r[1] || 0));
      el('path', { d: roundedTop(x, H - m.b - h, bw, h, Math.min(4, bw / 2)), fill: r[3] || color }, svg);
      if (i % every === 0) el('text', { x: x + bw / 2, y: H - 6, 'text-anchor': 'middle' }, ax).textContent = r[0];
      bind(el('rect', { x: m.l + i * slot, y: m.t, width: slot, height: H - m.t - m.b, fill: 'transparent' }, svg), r);
    });
  }
  function roundedTop(x, y, w, h, r) {
    if (h <= 0) return '';
    r = Math.min(r, h);
    return `M${x} ${y + h}V${y + r}Q${x} ${y} ${x + r} ${y}H${x + w - r}Q${x + w} ${y} ${x + w} ${y + r}V${y + h}Z`;
  }

  window.Charts = { line, bars, css, fmtNum, esc, showTip, hideTip };
})();
