// Canvas map viewer: WZ footholds, ropes, ladders and portals, the nav graph on top, stuck heatmap; pan and zoom.
'use strict';
(function () {
  const TYPES = ['WALK', 'JUMP', 'DROP', 'CLIMB', 'PORTAL', 'TELEPORT', 'FLASH_JUMP'];
  const TYPE_SLOT = { WALK: '--s1', JUMP: '--s2', DROP: '--s3', CLIMB: '--s4', PORTAL: '--s5', TELEPORT: '--s7', FLASH_JUMP: '--s6' };
  const SOURCE_NAMES = ['built graph', 'night bake', 'learned from players'];
  const SOURCE_SLOT = ['--muted', '--s7', '--s2'];
  // learned-cost ramp (sequential, one hue: orange), from "a little dearer" to "nearly always fails"
  const COST_RAMP = ['#f6c5ae', '#f0a07f', '#eb6834', '#c4501f', '#8f3511'];

  const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();

  class MapView {
    constructor(canvas, tipEl) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.tipEl = tipEl;
      this.geo = null;
      this.graph = null;
      this.stuck = [];
      this.opts = {
        layers: { footholds: true, ropes: true, portals: true, regions: false, edges: true, stuck: true },
        types: Object.fromEntries(TYPES.map(t => [t, true])),
        sources: [true, true, true],
        skills: { TELEPORT: false, FLASH_JUMP: false },
        colorBy: 'type',
      };
      this.view = { s: 1, x: 0, y: 0 };
      this.onPortal = null;
      this.onPick = null;      // route preview: (x, y) in map coordinates when the picker is on
      this.route = null;       // { a: [x, y], b: [x, y], edges: [...] } from the route preview
      this.highlight = null;   // Set of region ids to ring (a dead end from the World tab)
      this.hover = null;
      this.pinned = null;
      this._bind();
      new ResizeObserver(() => this.resize()).observe(canvas.parentElement);
    }

    setMap(geo, stuck) {
      this.geo = geo;
      this.stuck = stuck || [];
      this.graph = null;
      this.paths = [];
      this.route = null;
      this.highlight = null;
      this.fit();
    }

    setGraph(graph) {
      this.graph = graph;
      this.paths = graph ? graph.edges.map(e => ({ e, pts: edgePath(e) })) : [];
      this.draw();
    }

    resize() {
      const r = this.canvas.parentElement.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      this.canvas.width = Math.max(1, Math.round(r.width * dpr));
      this.canvas.height = Math.max(1, Math.round(r.height * dpr));
      this.dpr = dpr;
      this.w = r.width;
      this.h = r.height;
      this.draw();
    }

    extent() {
      const g = this.geo;
      let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
      const add = (x, y) => { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; };
      for (const f of g.footholds) { add(f[1], f[2]); add(f[3], f[4]); }
      for (const r of g.ropes) { add(r[0], r[1]); add(r[0], r[2]); }
      for (const p of g.portals) add(p[2], p[3]);
      if (x0 === Infinity && g.bounds) { x0 = g.bounds[0]; y0 = g.bounds[1]; x1 = x0 + g.bounds[2]; y1 = y0 + g.bounds[3]; }
      if (x0 === Infinity) { x0 = -400; y0 = -300; x1 = 400; y1 = 300; }
      return [x0, y0, x1, y1];
    }

    fit() {
      if (!this.geo) return;
      if (!this.w) this.resize();
      const [x0, y0, x1, y1] = this.extent();
      const pad = 40;
      const s = Math.min((this.w - 2 * pad) / Math.max(1, x1 - x0), (this.h - 2 * pad) / Math.max(1, y1 - y0));
      this.view.s = Math.max(0.02, Math.min(8, s));
      this.view.x = (this.w - (x1 - x0) * this.view.s) / 2 - x0 * this.view.s;
      this.view.y = (this.h - (y1 - y0) * this.view.s) / 2 - y0 * this.view.s;
      this.draw();
    }

    zoom(f, cx = this.w / 2, cy = this.h / 2) {
      const s = Math.max(0.02, Math.min(12, this.view.s * f));
      const k = s / this.view.s;
      this.view.x = cx - (cx - this.view.x) * k;
      this.view.y = cy - (cy - this.view.y) * k;
      this.view.s = s;
      this.draw();
    }

    centerOn(x, y, s) {
      if (s) this.view.s = s;
      this.view.x = this.w / 2 - x * this.view.s;
      this.view.y = this.h / 2 - y * this.view.s;
      this.draw();
    }

    edgeVisible(e) {
      const type = e[2];
      if (!this.opts.types[type]) return false;
      if ((type === 'TELEPORT' || type === 'FLASH_JUMP') && !this.opts.skills[type]) return false;
      return this.opts.sources[e[10]];
    }

    edgeColor(e) {
      if (this.opts.colorBy === 'source') return css(SOURCE_SLOT[e[10]]);
      if (this.opts.colorBy === 'cost') {
        const f = e[11];
        if (f > 1.0001) {
          const t = Math.min(1, Math.log(f) / Math.log(8));
          return COST_RAMP[Math.min(COST_RAMP.length - 1, Math.floor(t * COST_RAMP.length))];
        }
        return e[13] > 0 ? css('--s1') : css('--wall');
      }
      return css(TYPE_SLOT[e[2]] || '--muted');
    }

    draw() {
      if (this.raf) return;
      this.raf = requestAnimationFrame(() => { this.raf = 0; this._draw(); });
    }

    _draw() {
      const ctx = this.ctx, v = this.view;
      ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      ctx.clearRect(0, 0, this.w, this.h);
      if (!this.geo) return;
      const X = x => x * v.s + v.x, Y = y => y * v.s + v.y;
      const L = this.opts.layers;

      if (L.stuck && this.stuck.length) {
        const max = Math.max(...this.stuck.map(s => s[3]));
        const red = css('--s8');
        ctx.save();
        for (const s of this.stuck) {
          const r = Math.max(16, 40 * v.s) * (0.7 + 0.6 * Math.sqrt(s[3] / max));
          const g = ctx.createRadialGradient(X(s[0]), Y(s[1]), 0, X(s[0]), Y(s[1]), r);
          g.addColorStop(0, withAlpha(red, 0.35 + 0.55 * s[3] / max));
          g.addColorStop(1, withAlpha(red, 0));
          ctx.fillStyle = g;
          ctx.beginPath(); ctx.arc(X(s[0]), Y(s[1]), r, 0, Math.PI * 2); ctx.fill();
        }
        ctx.restore();
      }

      if (L.regions && this.graph) {
        ctx.strokeStyle = css('--grid'); ctx.lineWidth = 1;
        ctx.fillStyle = css('--muted'); ctx.font = '10px system-ui';
        for (const r of this.graph.regions) {
          ctx.strokeRect(X(r[2]) - 3, Y(r[3]) - 3, (r[4] - r[2]) * v.s + 6, (r[5] - r[3]) * v.s + 6);
          if (v.s > 0.4) ctx.fillText('r' + r[0], X(r[2]), Y(r[3]) - 5);
        }
      }

      if (L.footholds) {
        ctx.lineCap = 'round';
        for (const wall of [true, false]) {
          ctx.strokeStyle = css(wall ? '--wall' : '--foothold');
          ctx.lineWidth = wall ? 1 : 2;
          ctx.beginPath();
          for (const f of this.geo.footholds) {
            if (((f[5] & 1) === 1) !== wall) continue;
            ctx.moveTo(X(f[1]), Y(f[2])); ctx.lineTo(X(f[3]), Y(f[4]));
          }
          ctx.stroke();
        }
      }

      if (L.ropes) {
        ctx.strokeStyle = css('--rope'); ctx.lineWidth = 2;
        for (const r of this.geo.ropes) {
          const x = X(r[0]);
          ctx.beginPath();
          if (r[3]) { ctx.moveTo(x - 3, Y(r[1])); ctx.lineTo(x - 3, Y(r[2])); ctx.moveTo(x + 3, Y(r[1])); ctx.lineTo(x + 3, Y(r[2])); }
          else { ctx.setLineDash([4, 3]); ctx.moveTo(x, Y(r[1])); ctx.lineTo(x, Y(r[2])); }
          ctx.stroke(); ctx.setLineDash([]);
          if (r[3]) {
            ctx.lineWidth = 1; ctx.beginPath();
            for (let y = r[1]; y <= r[2]; y += 12) { ctx.moveTo(x - 3, Y(y)); ctx.lineTo(x + 3, Y(y)); }
            ctx.stroke(); ctx.lineWidth = 2;
          }
        }
      }

      if (L.edges && this.graph) {
        const surface = css('--map-bg');
        // built graph first, then the bake, then players' jumps on top
        for (const src of [0, 1, 2]) {
          for (const p of this.paths) {
            const e = p.e;
            if (e[10] !== src || !this.edgeVisible(e)) continue;
            const color = this.edgeColor(e);
            const hot = this.hover && this.hover.e === e || this.pinned && this.pinned.e === e;
            const width = (hot ? 2 : 0) + (src === 2 ? 3 : 1.6) + (this.opts.colorBy === 'cost' && e[11] > 1 ? 1 : 0);
            if (src === 2) { strokePath(ctx, p.pts, X, Y, surface, width + 3, []); }
            strokePath(ctx, p.pts, X, Y, color, width, src === 1 ? [6, 4] : e[2] === 'TELEPORT' ? [2, 3] : []);
            arrow(ctx, p.pts, X, Y, color, v.s);
            if (hot && (e[7] !== e[8])) { // launch window
              ctx.strokeStyle = color; ctx.lineWidth = 5; ctx.globalAlpha = 0.45;
              ctx.beginPath(); ctx.moveTo(X(e[7]), Y(e[4])); ctx.lineTo(X(e[8]), Y(e[4])); ctx.stroke();
              ctx.globalAlpha = 1;
            }
          }
        }
      }

      if (this.highlight && this.graph) {
        ctx.strokeStyle = css('--bad'); ctx.lineWidth = 3; ctx.setLineDash([6, 4]);
        for (const r of this.graph.regions) {
          if (!this.highlight.has(r[0])) continue;
          ctx.strokeRect(X(r[2]) - 8, Math.min(Y(r[3]), Y(r[5])) - 8, (r[4] - r[2]) * v.s + 16, Math.abs(r[5] - r[3]) * v.s + 16);
        }
        ctx.setLineDash([]);
      }

      if (this.route) {
        const R = this.route, col = css('--accent'), halo = css('--map-bg');
        let at = R.a;
        for (const e of R.edges || []) {
          // the walk along the platform to the launch spot, then the move itself
          strokePath(ctx, [at, [e[3], e[4]]], X, Y, halo, 9, []);
          strokePath(ctx, [at, [e[3], e[4]]], X, Y, col, 4, [2, 5]);
          const pts = edgePath(e);
          strokePath(ctx, pts, X, Y, halo, 9, []);
          strokePath(ctx, pts, X, Y, col, 4, []);
          arrow(ctx, pts, X, Y, col, Math.max(v.s, 0.3));
          at = [e[5], e[6]];
        }
        if (R.edges && R.b) { strokePath(ctx, [at, R.b], X, Y, halo, 9, []); strokePath(ctx, [at, R.b], X, Y, col, 4, [2, 5]); }
        for (const [pt, label] of [[R.a, 'A'], [R.b, 'B']]) {
          if (!pt) continue;
          ctx.fillStyle = col; ctx.strokeStyle = halo; ctx.lineWidth = 2;
          ctx.beginPath(); ctx.arc(X(pt[0]), Y(pt[1]), 9, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
          ctx.fillStyle = halo; ctx.font = '700 11px system-ui'; ctx.textAlign = 'center';
          ctx.fillText(label, X(pt[0]), Y(pt[1]) + 4); ctx.textAlign = 'start';
        }
      }

      if (L.portals) {
        ctx.fillStyle = css('--s5'); ctx.strokeStyle = css('--map-bg'); ctx.lineWidth = 1.5;
        ctx.font = '11px system-ui';
        for (const p of this.geo.portals) {
          const x = X(p[2]), y = Y(p[3]);
          ctx.beginPath(); ctx.moveTo(x, y - 12); ctx.lineTo(x - 6, y); ctx.lineTo(x + 6, y); ctx.closePath();
          ctx.fill(); ctx.stroke();
        }
      }

      if (L.stuck && this.stuck.length && v.s > 0.5) {
        ctx.fillStyle = css('--text'); ctx.font = '600 11px system-ui'; ctx.textAlign = 'center';
        for (const s of this.stuck) ctx.fillText(String(s[3]), X(s[0]), Y(s[1]) + 4);
        ctx.textAlign = 'start';
      }
    }

    // ── interaction ──
    _bind() {
      const c = this.canvas;
      let drag = null;
      c.addEventListener('pointerdown', ev => {
        drag = { x: ev.clientX, y: ev.clientY, vx: this.view.x, vy: this.view.y, moved: false };
        c.setPointerCapture(ev.pointerId);
        c.classList.add('dragging');
      });
      c.addEventListener('pointermove', ev => {
        if (drag) {
          const dx = ev.clientX - drag.x, dy = ev.clientY - drag.y;
          if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
          this.view.x = drag.vx + dx; this.view.y = drag.vy + dy;
          this.draw();
          return;
        }
        this._hover(ev);
      });
      c.addEventListener('pointerup', ev => {
        c.classList.remove('dragging');
        const wasClick = drag && !drag.moved;
        drag = null;
        if (wasClick) this._click(ev);
      });
      c.addEventListener('pointerleave', () => { this.hover = null; this.tipEl.hidden = true; this.draw(); });
      c.addEventListener('wheel', ev => {
        ev.preventDefault();
        const r = c.getBoundingClientRect();
        this.zoom(Math.exp(-ev.deltaY * 0.0015), ev.clientX - r.left, ev.clientY - r.top);
      }, { passive: false });
    }

    _pick(ev) {
      if (!this.geo) return null;
      const r = this.canvas.getBoundingClientRect();
      const mx = ev.clientX - r.left, my = ev.clientY - r.top, v = this.view;
      const X = x => x * v.s + v.x, Y = y => y * v.s + v.y;
      let best = null, bd = 8;
      if (this.opts.layers.portals) for (const p of this.geo.portals) {
        const d = Math.hypot(X(p[2]) - mx, Y(p[3]) - 6 - my);
        if (d < bd) { bd = d; best = { kind: 'portal', p }; }
      }
      if (best) return best;
      if (this.opts.layers.edges && this.graph) for (const p of this.paths) {
        if (!this.edgeVisible(p.e)) continue;
        for (let i = 1; i < p.pts.length; i++) {
          const d = segDist(mx, my, X(p.pts[i - 1][0]), Y(p.pts[i - 1][1]), X(p.pts[i][0]), Y(p.pts[i][1]));
          if (d < bd) { bd = d; best = { kind: 'edge', e: p.e }; }
        }
      }
      if (best) return best;
      if (this.opts.layers.stuck) for (const s of this.stuck) {
        const d = Math.hypot(X(s[0]) - mx, Y(s[1]) - my);
        if (d < Math.max(14, 25 * v.s) && d < (bd + 20)) { bd = d; best = { kind: 'stuck', s }; }
      }
      return best;
    }

    _hover(ev) {
      const hit = this._pick(ev);
      const prev = this.hover && this.hover.e;
      this.hover = hit && hit.kind === 'edge' ? hit : null;
      if ((this.hover && this.hover.e) !== prev) this.draw();
      if (!hit) { this.tipEl.hidden = true; return; }
      Charts.showTip(describe(hit), ev);
    }

    _click(ev) {
      if (this.onPick) {
        const r = this.canvas.getBoundingClientRect(), v = this.view;
        this.onPick(Math.round((ev.clientX - r.left - v.x) / v.s), Math.round((ev.clientY - r.top - v.y) / v.s));
        return;
      }
      const hit = this._pick(ev);
      if (hit && hit.kind === 'portal' && hit.p[4] && hit.p[4] !== 999999999 && this.onPortal) {
        this.onPortal(hit.p[4]);
      }
      this.pinned = hit && hit.kind === 'edge' ? hit : null;
      this.draw();
    }
  }

  function describe(hit) {
    const E = Charts.esc;
    if (hit.kind === 'portal') {
      const p = hit.p;
      return '<b>Portal ' + E(p[0]) + '</b><div class="row"><span>type</span><b>' + p[1] + '</b></div>' +
        (p[4] && p[4] !== 999999999 ? '<div class="row"><span>to map</span><b>' + p[4] + '</b></div><div class="muted">click to open it</div>' : '');
    }
    if (hit.kind === 'stuck') {
      const s = hit.s;
      return '<b>Stuck: ' + E(s[2]) + '</b><div class="row"><span>events</span><b>' + s[3] + '</b></div>' +
        '<div class="row"><span>spot</span><b>' + s[0] + ', ' + s[1] + '</b></div>' +
        (s[4] ? '<div>edge ' + E(s[4]) + '</div>' : '') +
        (s[5] ? '<div class="muted">last ' + new Date(s[5]).toLocaleString() + '</div>' : '');
    }
    const e = hit.e;
    const learned = e[12] || e[13];
    return '<b>' + E(e[2]) + ' r' + e[0] + ' → r' + e[1] + '</b>' +
      '<div class="row"><span>source</span><b>' + SOURCE_NAMES[e[10]] + '</b></div>' +
      '<div class="row"><span>built cost</span><b>' + e[9] + ' ms</b></div>' +
      '<div class="row"><span>learned cost ×</span><b>' + e[11].toFixed(2) + '</b></div>' +
      (learned ? '<div class="row"><span>failures / successes</span><b>' + e[12] + ' / ' + e[13] + '</b></div>' : '') +
      '<div class="row"><span>launch x</span><b>' + (e[7] === e[8] ? e[7] : e[7] + '..' + e[8]) + '</b></div>' +
      '<div class="row"><span>from → to</span><b>' + e[3] + ',' + e[4] + ' → ' + e[5] + ',' + e[6] + '</b></div>';
  }

  // An edge as a polyline in world space: jumps arc up, drops fall, the rest go straight.
  function edgePath(e) {
    const [sx, sy, ex, ey] = [e[3], e[4], e[5], e[6]];
    const type = e[2];
    let cx, cy;
    if (type === 'JUMP' || type === 'FLASH_JUMP') {
      cx = (sx + ex) / 2; cy = Math.min(sy, ey) - Math.max(25, Math.abs(ex - sx) * 0.35);
    } else if (type === 'DROP') {
      cx = sx + (ex - sx) * 0.85; cy = sy;
    } else {
      return [[sx, sy], [ex, ey]];
    }
    const pts = [];
    for (let i = 0; i <= 12; i++) {
      const t = i / 12, u = 1 - t;
      pts.push([u * u * sx + 2 * u * t * cx + t * t * ex, u * u * sy + 2 * u * t * cy + t * t * ey]);
    }
    return pts;
  }

  function strokePath(ctx, pts, X, Y, color, width, dash) {
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.setLineDash(dash); ctx.lineCap = 'round';
    ctx.beginPath();
    pts.forEach((p, i) => i ? ctx.lineTo(X(p[0]), Y(p[1])) : ctx.moveTo(X(p[0]), Y(p[1])));
    ctx.stroke(); ctx.setLineDash([]);
  }

  function arrow(ctx, pts, X, Y, color, s) {
    if (s < 0.25) return;
    const a = pts[pts.length - 2], b = pts[pts.length - 1];
    const ang = Math.atan2(Y(b[1]) - Y(a[1]), X(b[0]) - X(a[0]));
    const x = X(b[0]), y = Y(b[1]), k = 7;
    ctx.fillStyle = color;
    ctx.beginPath(); ctx.moveTo(x, y);
    ctx.lineTo(x - k * Math.cos(ang - 0.45), y - k * Math.sin(ang - 0.45));
    ctx.lineTo(x - k * Math.cos(ang + 0.45), y - k * Math.sin(ang + 0.45));
    ctx.closePath(); ctx.fill();
  }

  function segDist(px, py, ax, ay, bx, by) {
    const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
    const t = l ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l)) : 0;
    return Math.hypot(px - ax - t * dx, py - ay - t * dy);
  }

  function withAlpha(hex, a) {
    const h = hex.replace('#', '');
    const n = parseInt(h.length === 3 ? h.split('').map(c => c + c).join('') : h, 16);
    return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`;
  }

  // The platform a point stands on: the region under (or just above) it whose span covers x, else the nearest one.
  // Same rule as region_at in slums-dashboard-extras (the dead-end check), so the two agree on where a spot is.
  function regionAt(regions, x, y) {
    let best = null, bd = null;
    for (const r of regions) {
      if (r[1] !== 0) continue;
      const [x1, y1, x2, y2] = [r[2], r[3], r[4], r[5]];
      if (x < x1 - 20 || x > x2 + 20) continue;
      const ry = x2 === x1 ? y1 : y1 + (y2 - y1) * (Math.min(Math.max(x, x1), x2) - x1) / (x2 - x1);
      const d = ry - y;
      if (d < -40) continue;
      if (bd === null || d < bd) { best = r[0]; bd = d; }
    }
    if (best === null) {
      let far = null;
      for (const r of regions) {
        const cx = Math.min(Math.max(x, r[2]), r[4]), cy = Math.min(Math.max(y, Math.min(r[3], r[5])), Math.max(r[3], r[5]));
        const d = (cx - x) ** 2 + (cy - y) ** 2;
        if (far === null || d < far) { best = r[0]; far = d; }
      }
    }
    return best;
  }

  // Route preview: the cheapest chain of nav edges from point a to point b over the edges ok(e) allows. A move costs
  // its built cost (ms) times its learned multiplier, and walking along a platform costs its distance at base speed
  // (about 125 px a second). Each platform keeps the x a bot reached it at, so the walking estimate is approximate.
  const WALK_MS_PER_PX = 8;
  function findRoute(graph, a, b, ok) {
    const ra = regionAt(graph.regions, a[0], a[1]), rb = regionAt(graph.regions, b[0], b[1]);
    if (ra === null || rb === null) return { ra, rb, edges: null };
    const out = new Map();
    for (const e of graph.edges) if (ok(e)) { if (!out.has(e[0])) out.set(e[0], []); out.get(e[0]).push(e); }
    const best = new Map([[ra, { cost: 0, x: a[0], prev: null, e: null }]]);
    const open = [[0, ra]], done = new Set();
    while (open.length) {
      let k = 0;
      for (let i = 1; i < open.length; i++) if (open[i][0] < open[k][0]) k = i;
      const [, r] = open.splice(k, 1)[0];
      if (done.has(r)) continue;
      done.add(r);
      if (r === rb) break;
      const cur = best.get(r);
      for (const e of out.get(r) || []) {
        const launch = Math.min(Math.max(cur.x, e[7]), e[8]);
        const c = cur.cost + Math.abs(launch - cur.x) * WALK_MS_PER_PX + e[9] * Math.max(1, e[11]) + 50;
        const nb = best.get(e[1]);
        if (!nb || c < nb.cost) { best.set(e[1], { cost: c, x: e[5], prev: r, e }); open.push([c, e[1]]); }
      }
    }
    if (!best.has(rb)) return { ra, rb, edges: null };
    const edges = [];
    for (let r = rb; best.get(r).e; r = best.get(r).prev) edges.unshift(best.get(r).e);
    const end = best.get(rb);
    return { ra, rb, edges, ms: Math.round(end.cost + Math.abs(b[0] - end.x) * WALK_MS_PER_PX) };
  }

  window.MapView = MapView;
  window.MapView.regionAt = regionAt;
  window.MapView.findRoute = findRoute;
  window.MapView.TYPES = TYPES;
  window.MapView.TYPE_SLOT = TYPE_SLOT;
  window.MapView.SOURCE_SLOT = SOURCE_SLOT;
  window.MapView.SOURCE_NAMES = SOURCE_NAMES;
  window.MapView.COST_RAMP = COST_RAMP;
})();
