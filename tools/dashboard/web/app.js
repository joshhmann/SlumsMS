// SlumsMS admin dashboard: reads the JSON snapshots the server and the map export write under data/.
'use strict';
(function () {
  const DATA = 'data/';
  const $ = id => document.getElementById(id);
  const E = s => Charts.esc(s);
  const N = v => Charts.fmtNum(v);
  const C = n => Charts.css(n);
  const SLOTS = ['--s1', '--s2', '--s3', '--s4', '--s5', '--s6', '--s7', '--s8'];
  const ACTIVITY_ORDER = ['grind', 'travel', 'town', 'break', 'idle'];

  const state = { live: null, days: null, day: null, series: null, names: null, index: null, learning: null, tab: 'map',
    map: null, mapId: null, key: null, dailies: {}, ops: null, gm: null };
  const TABS = ['map', 'learning', 'bots', 'economy', 'health', 'gm'];
  const CHAT_TIERS = ['llm', 'scripted', 'emote', 'ignored', 'flagged'];

  async function getJSON(path, fresh) {
    const r = await fetch(DATA + path + (fresh ? (path.includes('?') ? '&' : '?') + 't=' + Date.now() : ''), { cache: fresh ? 'no-store' : 'default' });
    if (!r.ok) throw new Error(path + ': ' + r.status);
    return r.json();
  }
  async function getText(path) {
    const r = await fetch(DATA + path + '?t=' + Date.now(), { cache: 'no-store' });
    return r.ok ? r.text() : null;
  }
  const mapName = id => {
    const n = state.names && state.names[id];
    return n ? (n[0] && n[1] ? n[0] + ' : ' + n[1] : n[0] || n[1]) : '';
  };

  // ── tabs and routing ───────────────────────────────────────────────────────
  function showTab(tab) {
    state.tab = tab;
    document.querySelectorAll('.tabs button').forEach(b => b.setAttribute('aria-selected', b.dataset.tab === tab));
    document.querySelectorAll('.tab').forEach(s => s.classList.toggle('active', s.id === 'tab-' + tab));
    render();
  }
  document.querySelectorAll('.tabs button').forEach(b => b.addEventListener('click', () => {
    if (b.dataset.tab === 'map' && state.mapId) location.hash = 'map/' + state.mapId + (state.key ? '/' + state.key : '');
    else location.hash = b.dataset.tab;
  }));
  function route() {
    const parts = location.hash.replace(/^#/, '').split('/');
    const tab = TABS.includes(parts[0]) ? parts[0] : 'map';
    if (tab === 'map' && parts[1] && +parts[1] !== state.mapId) openMap(+parts[1], parts[2]);
    showTab(tab);
  }
  window.addEventListener('hashchange', route);
  let resizeTimer;
  window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 150); });

  function render() {
    try {
      if (state.tab === 'learning') renderLearning();
      else if (state.tab === 'bots') renderBots();
      else if (state.tab === 'economy') renderEconomy();
      else if (state.tab === 'health') renderHealth();
      else if (state.tab === 'gm') renderGm();
    } catch (e) { console.error(e); }
  }

  // ── status line ────────────────────────────────────────────────────────────
  function renderStatus() {
    const s = $('status');
    if (!state.live) {
      s.innerHTML = '<span class="dot" style="background:' + C('--muted') + '"></span>no live snapshot (exporter off?)';
      return;
    }
    const age = (Date.now() - state.live.t) / 1000;
    const color = age < 180 ? C('--good') : age < 900 ? C('--warn') : C('--bad');
    const label = age < 180 ? 'live' : age < 900 ? 'delayed' : 'stale';
    s.innerHTML = '<span class="dot" style="background:' + color + '"></span>' + label + ' · snapshot ' +
      (age < 90 ? Math.round(age) + 's' : Math.round(age / 60) + ' min') + ' ago';
  }

  // ── ops strip: slumsms-monitor results (ticket 42), copied by slums-dashboard-extras ──
  function ago(sec) {
    return sec < 90 ? Math.round(sec) + 's' : sec < 5400 ? Math.round(sec / 60) + ' min' : (sec / 3600).toFixed(1) + ' h';
  }
  function renderOps() {
    const host = $('ops-strip');
    const o = state.ops;
    if (!o || !o.targets || !o.targets.length) { host.hidden = true; return; }
    host.hidden = false;
    host.innerHTML = o.targets.map(t => {
      if (t.error) return '<span class="target"><b>' + E(t.target) + '</b><span class="pill fail"><i style="background:' + C('--muted') + '"></i>' + E(t.error) + '</span></span>';
      const age = Date.now() / 1000 - (t.at || 0);
      const stale = age > 300;
      const checks = Object.values(t.checks || {});
      const failing = checks.filter(c => !c.ok && !c.skipped);
      const head = stale ? ['--warn', 'monitor silent ' + ago(age)] : failing.length ? ['--bad', failing.length + ' failing'] : ['--good', 'all ok'];
      return '<span class="target"><b>' + E(t.label || t.target) + '</b>' +
        '<span class="pill' + (failing.length || stale ? ' fail' : '') + '" title="checked ' + ago(age) + ' ago"><i style="background:' + C(head[0]) + '"></i>' + head[1] + '</span>' +
        checks.map(c => {
          const col = c.skipped ? '--muted' : c.ok ? '--good' : '--bad';
          return '<span class="pill' + (!c.ok && !c.skipped ? ' fail' : '') + '" title="' + E(c.detail + (c.skipped ? ' (skipped)' : '')) + '"><i style="background:' + C(col) + '"></i>' + E(c.name) + '</span>';
        }).join('') + '</span>';
    }).join('');
  }

  // ── map viewer ─────────────────────────────────────────────────────────────
  const view = new MapView($('map-canvas'), $('chart-tip'));
  view.onPortal = id => { location.hash = 'map/' + id; };

  function buildToggles() {
    $('type-toggles').innerHTML = MapView.TYPES.map(t =>
      '<label class="type-row"><input type="checkbox" data-type="' + t + '" checked><i style="background:' + C(MapView.TYPE_SLOT[t]) +
      '"></i>' + t.replace('_', ' ').toLowerCase() + '<span class="n" id="n-' + t + '"></span></label>').join('');
    document.querySelectorAll('[data-type]').forEach(c => c.addEventListener('change', () => { view.opts.types[c.dataset.type] = c.checked; view.draw(); }));
    document.querySelectorAll('[data-layer]').forEach(c => c.addEventListener('change', () => { view.opts.layers[c.dataset.layer] = c.checked; view.draw(); }));
    document.querySelectorAll('[data-source]').forEach(c => c.addEventListener('change', () => { view.opts.sources[+c.dataset.source] = c.checked; view.draw(); }));
    $('skill-tp').addEventListener('change', e => { view.opts.skills.TELEPORT = e.target.checked; view.draw(); });
    $('skill-fj').addEventListener('change', e => { view.opts.skills.FLASH_JUMP = e.target.checked; view.draw(); });
    $('color-by').addEventListener('change', e => { view.opts.colorBy = e.target.value; renderLegend(); view.draw(); });
    $('fit').addEventListener('click', () => view.fit());
    $('zoom-in').addEventListener('click', () => view.zoom(1.4));
    $('zoom-out').addEventListener('click', () => view.zoom(1 / 1.4));
    $('profile').addEventListener('change', e => { location.hash = 'map/' + state.mapId + '/' + e.target.value; loadGraph(e.target.value); });
    $('map-search').addEventListener('input', search);
    $('only-graphs').addEventListener('change', search);
    renderLegend();
  }

  function renderLegend() {
    const by = view.opts.colorBy;
    let html = '';
    if (by === 'cost') {
      html = '<div>Learned cost multiplier (failures make an edge dearer)</div><div class="ramp" style="background:linear-gradient(90deg,' +
        MapView.COST_RAMP.join(',') + ')"></div><div class="ends"><span>×1.1</span><span>×3</span><span>×8</span></div>' +
        '<div><span class="swatch" style="border-color:' + C('--s1') + '"></span> walked fine &nbsp; <span class="swatch" style="border-color:' +
        C('--wall') + '"></span> no record</div>';
    } else if (by === 'source') {
      html = MapView.SOURCE_NAMES.map((n, i) => '<div><span class="swatch' + (i === 1 ? ' dash' : i === 2 ? ' ring' : '') +
        '" style="border-color:' + C(MapView.SOURCE_SLOT[i]) + '"></span> ' + n + '</div>').join('');
    }
    html += '<div><span class="swatch" style="border-color:' + C('--rope') + ';border-top-style:dashed"></span> rope, ' +
      '<span style="color:' + C('--s5') + '">▲</span> portal (click to follow), <span style="color:' + C('--s8') + '">●</span> stuck spot</div>';
    $('map-legend').innerHTML = html;
  }

  async function ensureIndex() {
    if (!state.names) {
      try { state.names = await getJSON('nav/names.json'); } catch (e) { state.names = {}; }
    }
    if (!state.index) {
      try {
        const idx = await getJSON('nav/index.json', true);
        state.index = new Map(idx.maps.map(r => [r[0], r]));
        state.indexMeta = idx;
      } catch (e) { state.index = new Map(); }
    }
  }

  async function search() {
    await ensureIndex();
    const q = $('map-search').value.trim().toLowerCase();
    const only = $('only-graphs').checked;
    const out = [];
    if (q) {
      const ids = only ? [...state.index.keys()] : Object.keys(state.names).map(Number);
      for (const id of ids) {
        const name = mapName(id).toLowerCase();
        if (String(id).startsWith(q) || name.includes(q)) out.push(id);
        if (out.length >= 400) break;
      }
    } else {
      // no query: the maps with the most stuck events
      out.push(...[...state.index.values()].sort((a, b) => b[8] - a[8] || a[0] - b[0]).slice(0, 30).map(r => r[0]));
    }
    out.sort((a, b) => (state.index.has(b) - state.index.has(a)) || ((state.index.get(b)?.[8] || 0) - (state.index.get(a)?.[8] || 0)) || a - b);
    $('map-results').innerHTML = out.slice(0, 60).map(id => {
      const r = state.index.get(id);
      const tag = r ? r[3] + ' profiles' + (r[8] ? ' · ' + r[8] + ' stuck' : '') : 'no graph';
      return '<li data-id="' + id + '"' + (id === state.mapId ? ' class="sel"' : '') + '><span class="tag">' + tag +
        '</span><span class="id">' + id + '</span>' + E(mapName(id) || '?') + '</li>';
    }).join('') || '<li class="muted">no match</li>';
    $('map-results').querySelectorAll('li[data-id]').forEach(li => li.addEventListener('click', () => { location.hash = 'map/' + li.dataset.id; }));
  }

  async function openMap(id, key) {
    await ensureIndex();
    state.mapId = id;
    $('map-empty').textContent = 'Loading map ' + id + '…';
    $('map-empty').hidden = false;
    let doc;
    try {
      doc = await getJSON('nav/maps/' + id + '.json', true);
    } catch (e) {
      $('map-empty').textContent = 'Map ' + id + ' (' + (mapName(id) || 'unknown') + ') has no export yet: it has no cached nav graph, ' +
        'or the map export has not run since. Run the map export with this id to add it.';
      view.setMap(null); view.geo = null; view.draw();
      $('map-info').innerHTML = '';
      return;
    }
    $('map-empty').hidden = true;
    state.map = doc;
    view.setMap(doc, doc.stuck);
    const sel = $('profile');
    sel.innerHTML = doc.profiles.map(p => '<option value="' + p[0] + '">speed ' + p[1] + ' / jump ' + p[2] + (p[3] ? ' snowshoes' : '') +
      (p[1] === 100 && p[2] === 100 ? ' (base)' : '') + ' · ' + p[4] + ' edges' + (p[5] ? ', ' + p[5] + ' baked' : '') + '</option>').join('');
    const pick = doc.profiles.find(p => p[0] === key) || doc.profiles.find(p => p[1] === 100 && p[2] === 100 && !p[3]) || doc.profiles[0];
    if (pick) { sel.value = pick[0]; await loadGraph(pick[0]); } else { view.setGraph(null); }
    renderMapInfo();
    search();
  }

  async function loadGraph(key) {
    state.key = key;
    try {
      const g = await getJSON('nav/graphs/' + key + '.json', true);
      view.setGraph(g);
      for (const t of MapView.TYPES) { const n = $('n-' + t); if (n) n.textContent = g.byType[t] || ''; }
      state.graph = g;
    } catch (e) {
      view.setGraph(null);
      state.graph = null;
    }
    renderMapInfo();
  }

  function renderMapInfo() {
    const d = state.map, g = state.graph;
    if (!d) return;
    const stuckTotal = d.stuck.reduce((a, s) => a + s[3], 0);
    $('map-info').innerHTML = '<h2>' + E(d.name || '?') + '</h2><div class="muted">' + E(d.street || '') + ' · ' + d.id + '</div>' +
      '<div class="kv">' +
      (g ? '<span>regions</span><b>' + g.regions.length + '</b><span>edges</span><b>' + g.edges.length + '</b>' +
        '<span>from the night bake</span><b>' + g.deepEdges + '</b><span>learned from players</span><b>' + g.playerEdges + '</b>' +
        '<span>with learned cost</span><b>' + g.learnedEdges + '</b><span>penalised now</span><b>' + g.penalised + '</b>' : '<span>graph</span><b>none</b>') +
      '<span>stuck events (' + d.stuckDays + ' d)</span><b>' + stuckTotal + '</b></div>' +
      '<div class="chart" id="map-stuck-days" style="height:70px;margin-top:6px"></div>';
    const days = Object.entries(d.stuckByDay || {}).slice(-30);
    Charts.bars($('map-stuck-days'), { rows: days.map(([k, v]) => [k.slice(5), v]), color: C('--s8'), valueName: 'stuck events', empty: 'no stuck events in 30 days' });
    $('stuck-list').innerHTML = d.stuck.length ? '<table class="t"><tr><th>Stuck spot</th><th>kind</th><th class="num">n</th></tr>' +
      d.stuck.slice(0, 12).map((s, i) => '<tr class="link" data-i="' + i + '" title="' + E(s[4] || '') + '"><td>' + s[0] + ', ' + s[1] +
        '</td><td>' + E(s[2]) + '</td><td class="num">' + s[3] + '</td></tr>').join('') + '</table>' : '';
    $('stuck-list').querySelectorAll('tr[data-i]').forEach(tr => tr.addEventListener('click', () => {
      const s = d.stuck[+tr.dataset.i]; view.centerOn(s[0], s[1], Math.max(view.view.s, 1.2));
    }));
  }

  // ── learning ───────────────────────────────────────────────────────────────
  async function loadLearning() {
    try { state.learning = await getJSON('nav/learning.json', true); } catch (e) { state.learning = null; }
    await ensureIndex();
    const [bake, stuck] = await Promise.all([getText('nav/bake-latest.md'), getText('nav/stuck-latest.md')]);
    $('bake-report').textContent = bake || 'No bake report yet.';
    $('stuck-report').textContent = stuck || 'No stuck report yet.';
  }

  function tiles(host, items) {
    $(host).innerHTML = items.map(t => '<div class="tile"><div class="label">' + E(t[0]) + '</div><div class="value">' + t[1] +
      '</div>' + (t[2] ? '<div class="sub">' + t[2] + '</div>' : '') + '</div>').join('');
  }

  function mapTable(host, head, rows) {
    $(host).innerHTML = rows.length ? '<table class="t"><tr>' + head.map((h, i) => '<th' + (i > 1 ? ' class="num"' : '') + '>' + h + '</th>').join('') + '</tr>' +
      rows.map(r => '<tr class="link" data-id="' + r[0] + '">' + r.map((c, i) => '<td' + (i > 1 ? ' class="num"' : '') + '>' + c + '</td>').join('') + '</tr>').join('') +
      '</table>' : '<div class="muted">Nothing yet.</div>';
    $(host).querySelectorAll('tr[data-id]').forEach(tr => tr.addEventListener('click', () => { location.hash = 'map/' + tr.dataset.id; }));
  }

  function renderLearning() {
    const L = state.learning;
    if (!L) {
      tiles('learn-tiles', [['Map export', 'not run yet', 'run the dashboard map export']]);
      return;
    }
    const t = L.today;
    const hist = L.history || [];
    const prev = hist.length > 1 ? hist[hist.length - 2] : null;
    const delta = (k) => prev ? (t[k] - prev[k] >= 0 ? '+' : '') + N(t[k] - prev[k]) + ' since ' + prev.day : '';
    tiles('learn-tiles', [
      ['Night bake edges', N(t.deepEdges), (t.deepGeneration ? 'generation ' + E(t.deepGeneration) : 'no bake yet') + (prev ? '<br>' + delta('deepEdges') : '')],
      ['Moves learned from players', N(t.playerEdges), t.playerMaps + ' maps' + (prev ? '<br>' + delta('playerEdges') : '')
        + (L.players && L.players.moves != null ? '<br>' + N(L.players.moves) + ' player moves seen' : '')],
      ['Edges penalised now', N(t.penalised), N(t.learnedEdges) + ' with a record'],
      ['Failures (faded)', N(t.failures), N(t.successes) + ' successes'],
      ['Stuck events today', N(t.stuckToday), 'UTC day'],
      ['Graphs cached', N(t.graphKeys), t.maps + ' maps'],
    ]);
    const xs = h => Date.parse(h.day + 'T12:00:00Z');
    Charts.line($('learn-edges'), { x: 'day', series: [
      { name: 'night bake', color: C('--s7'), points: hist.map(h => [xs(h), h.deepEdges]) },
      { name: 'learned from players', color: C('--s2'), points: hist.map(h => [xs(h), h.playerEdges]) },
    ], empty: 'history builds up one point a day' });
    Charts.bars($('learn-stuck'), { rows: Object.entries(L.stuckPerDay || {}).map(([d, v]) => [d.slice(5), v]), color: C('--s8'), valueName: 'stuck events' });
    Charts.line($('learn-pen'), { x: 'day', series: [
      { name: 'penalised edges', color: C('--s2'), points: hist.map(h => [xs(h), h.penalised]) },
      { name: 'edges with a record', color: C('--s1'), points: hist.map(h => [xs(h), h.learnedEdges]) },
    ] });
    Charts.bars($('learn-kinds'), { horizontal: true, rows: Object.entries(L.stuckTodayByKind || {}).sort((a, b) => b[1] - a[1]), color: C('--s8'), empty: 'no stuck events today' });
    mapTable('learn-improving', ['Map', 'Name', 'before /day', 'now /day'], (L.improving || []).map(r => [r[0], E(mapName(r[0])), r[1], r[2]]));
    mapTable('learn-worst', ['Map', 'Name', 'stuck 7 d', 'penalised'], (L.worst || []).map(r => [r[0], E(mapName(r[0])), r[1], r[2]]));
    // What the bots made of real players' moves (NavPlayerLearning, players/stats.json).
    const P = L.players || {};
    const OUT = { ADOPTED: 'learned', PROVE_FAILED: 'proof failed', SIGHTED: 'new, counting', ALREADY_JOINED: 'already known',
      SAME_REGION: 'same platform', UNKNOWN_REGION: 'unknown platform', NO_GRAPH: 'no graph', ROPE_SKILL: 'skill to/from rope', NO_MAP: 'no map' };
    Charts.bars($('learn-players'), { horizontal: true, rows: Object.entries(P.totals || {}).map(([k, v]) => [OUT[k] || k, v]).sort((a, b) => b[1] - a[1]),
      color: C('--s2'), empty: 'no player moves recorded yet' });
    mapTable('learn-player-maps', ['Map', 'Name', 'moves', 'known'], Object.entries(P.maps || {}).map(([id, c]) =>
      [id, E(mapName(+id)), Object.values(c).reduce((a, b) => a + b, 0), c.ALREADY_JOINED || 0]));
    const portals = P.portals || [];
    $('learn-portals').innerHTML = portals.length ? '<table class="t"><tr><th>From</th><th>To</th><th>Portal</th><th class="num">uses</th></tr>' +
      portals.map(r => { const [a, b] = r[0].split('>'); return '<tr><td>' + E(mapName(+a)) + '</td><td>' + E(mapName(+b)) + '</td><td>' + E(r[1]) + '</td><td class="num">' + r[2] + '</td></tr>'; }).join('') +
      '</table>' : '<div class="muted">Nothing yet.</div>';
  }

  // ── bots ───────────────────────────────────────────────────────────────────
  async function loadDays() {
    try { state.days = await getJSON('days.json', true); } catch (e) { state.days = { series: [], daily: [] }; }
    const sel = $('day-pick');
    const days = [...state.days.series].reverse();
    const cur = state.day || days[0];
    sel.innerHTML = days.map(d => '<option' + (d === cur ? ' selected' : '') + '>' + d + '</option>').join('');
    if (cur) await loadSeries(cur);
  }
  async function loadSeries(day) {
    state.day = day;
    try { state.series = await getJSON('series/' + day + '.json', true); } catch (e) { state.series = { points: [] }; }
  }
  $('day-pick').addEventListener('change', async e => { await loadSeries(e.target.value); render(); });

  function points() {
    const pts = (state.series && state.series.points) || [];
    // the live snapshot is newer than the last saved series point: show it too when viewing today
    const lv = state.live;
    if (lv && state.days && state.day === state.days.series[state.days.series.length - 1] && (!pts.length || pts[pts.length - 1].t < lv.t)) {
      return pts.concat([{ t: lv.t, on: lv.bots.online, tgt: lv.bots.target, pl: lv.bots.players, act: lv.bots.activity,
        heap: lv.health.heapUsedMb, cpu: lv.health.cpuPct, tick: lv.health.tickMsPerS, pool: lv.health.poolActive, gate: lv.health.gateUsed,
        meso: lv.economy.mesosToday }]);
    }
    return pts;
  }

  function renderBots() {
    const lv = state.live;
    if (!lv) { tiles('bot-tiles', [['Live snapshot', 'none', 'start the server with -Dsolomapling.dashboard=true']]); return; }
    const b = lv.bots;
    tiles('bot-tiles', [
      ['Bots online', N(b.online), 'target now ' + N(b.target)],
      ['Real players', N(b.players), ''],
      ['Roster', N(b.roster), 'peak online ' + N(b.peak)],
      ['Director', E(b.director), ''],
      ['Maps with bots', N(b.mapsWithBots), b.lod ? b.lod.fullMaps + ' watched by players' : ''],
      ['Moving in full detail', b.lod ? N(b.lod.dynamicBots) : '–', b.lod ? b.lod.activeMaps + ' active maps' : ''],
    ]);
    const pts = points();
    const series = [
      { name: 'online', color: C('--s1'), points: pts.map(p => [p.t, p.on]) },
      { name: 'target (population curve)', color: C('--s2'), dash: true, points: pts.map(p => [p.t, p.tgt]) },
    ];
    if (b.targetsByHour && b.targetsByHour.length === 24 && pts.length && state.day === state.days.series[state.days.series.length - 1]) {
      const d0 = new Date(pts[0].t); d0.setHours(0, 0, 0, 0);
      series.push({ name: 'curve, rest of today', color: C('--muted'), dash: true,
        points: b.targetsByHour.map((v, h) => [d0.getTime() + h * 3600e3, v]).filter(p => p[0] > Date.now()) });
    }
    series.push({ name: 'real players', color: C('--s3'), points: pts.map(p => [p.t, p.pl]) });
    Charts.line($('bot-online'), { x: 'time', series });
    const keys = new Set();
    pts.forEach(p => Object.keys(p.act || {}).forEach(k => keys.add(k)));
    let order = ACTIVITY_ORDER.filter(k => keys.has(k)).concat([...keys].filter(k => !ACTIVITY_ORDER.includes(k)).sort());
    const fold = order.slice(7);
    order = order.slice(0, 7);
    const act = order.map((k, i) => ({ name: k, color: C(SLOTS[i]), points: pts.map(p => [p.t, (p.act || {})[k] || 0]) }));
    if (fold.length) act.push({ name: 'other', color: C('--s8'), points: pts.map(p => [p.t, fold.reduce((a, k) => a + ((p.act || {})[k] || 0), 0)]) });
    Charts.line($('bot-activity'), { x: 'time', series: act });
    Charts.bars($('bot-levels'), { rows: b.levels.map(r => [r[0] + '–' + (r[0] + 9), r[1]]), valueName: 'bots' });
    Charts.bars($('bot-jobs'), { horizontal: true, rows: Object.entries(b.jobs).sort((x, y) => y[1] - x[1]).slice(0, 10)
      .map(([k, v]) => [k.toLowerCase().replace(/_/g, ' '), v]), valueName: 'bots' });
    Charts.bars($('bot-types'), { horizontal: true, rows: Object.entries(b.types).sort((x, y) => y[1] - x[1]).slice(0, 10), color: C('--s3'), valueName: 'bots' });
    mapTable('bot-maps', ['Map', 'Name', 'bots', 'avg level'], b.maps.slice(0, 20).map(r => [r[0], E(r[1] || mapName(r[0])), r[2], r[3]]));
  }

  // ── economy ────────────────────────────────────────────────────────────────
  async function loadDailies() {
    const want = ((state.days && state.days.daily) || []).slice(-30);
    await Promise.all(want.filter(d => !state.dailies[d]).map(async d => {
      try { state.dailies[d] = await getJSON('daily/' + d + '.json'); } catch (e) { /* skip */ }
    }));
  }

  function renderEconomy() {
    const lv = state.live;
    if (!lv) { tiles('econ-tiles', [['Live snapshot', 'none', '']]); return; }
    const ec = lv.economy;
    $('econ-source').innerHTML = ec.source === 'derived'
      ? '<b>Estimated intake.</b> The ticket 15 ledger is not in this build, so intake is each online bot\'s rise in mesos ' +
        'between snapshots, on the map it stands on (trades and shop sales count too, items are not counted, counts restart with the server). ' +
        'With the ledger merged this panel shows real per-map intake against the caps.'
      : '<b>Ticket 15 ledger.</b> ' + Object.entries(ec.caps || {}).map(([k, v]) => E(k) + ': ' + E(v)).join(' · ');
    const w = ec.wealth;
    tiles('econ-tiles', [
      ['Bot meso intake today', N(ec.mesosToday), ec.source],
      ['Items taken today', ec.itemsToday >= 0 ? N(ec.itemsToday) : 'n/a', ec.itemsToday >= 0 ? '' : 'needs the ledger'],
      ['Spent today', ec.spentToday != null ? N(ec.spentToday) : '–', 'online bots'],
      ['Mesos held by online bots', N(w.total), ''],
      ['Median bot', N(w.p50), 'p10 ' + N(w.p10) + ' · p90 ' + N(w.p90)],
      ['Richest bot', N(w.max), ''],
    ]);
    const capped = ec.maps.some(r => r[4] > 0);
    $('econ-maps').innerHTML = ec.maps.length ? '<table class="t"><tr><th>Map</th><th>Name</th><th class="num">mesos</th><th class="num">items</th>' +
      (capped ? '<th>against cap</th>' : '') + '<th class="num">refused</th></tr>' + ec.maps.map(r => {
        const pct = r[4] > 0 ? r[2] / r[4] : 0;
        return '<tr class="link" data-id="' + r[0] + '"><td>' + r[0] + '</td><td>' + E(r[1] || mapName(r[0])) + '</td><td class="num">' + N(r[2]) +
          '</td><td class="num">' + (r[3] >= 0 ? N(r[3]) : '–') + '</td>' +
          (capped ? '<td>' + (r[4] > 0 ? '<div class="meter" title="' + Math.round(pct * 100) + '% of ' + N(r[4]) + '"><span' + (pct > 1 ? ' class="over"' : '') +
            ' style="width:' + Math.min(100, pct * 100) + '%"></span></div>' : '–') + '</td>' : '') +
          '<td class="num">' + (r[6] || '') + '</td></tr>';
      }).join('') + '</table>' : '<div class="muted">No intake yet today.</div>';
    $('econ-maps').querySelectorAll('tr[data-id]').forEach(tr => tr.addEventListener('click', () => { location.hash = 'map/' + tr.dataset.id; }));
    Charts.bars($('econ-wealth'), { rows: w.hist, valueName: 'bots', color: C('--s4') });
    Charts.bars($('econ-bylevel'), { rows: w.medianByLevel.map(r => [r[0] + '–' + (r[0] + 9), r[1], r[2] + ' bots']), valueName: 'median mesos', color: C('--s4') });
    const days = Object.values(state.dailies).sort((a, b) => a.day < b.day ? -1 : 1).map(d => [d.day.slice(5), d.mesosToday || 0]);
    days.push(['today', ec.mesosToday]);
    Charts.bars($('econ-days'), { rows: days, valueName: 'mesos', color: C('--s1') });
    $('econ-bots').innerHTML = ec.bots.length ? '<table class="t"><tr><th>Bot</th><th>lv</th><th class="num">mesos</th><th class="num">cap</th></tr>' +
      ec.bots.slice(0, 15).map(r => '<tr><td>' + E(r[1]) + '</td><td>' + r[2] + '</td><td class="num">' + N(r[3]) + '</td><td class="num">' +
        (r[4] > 0 ? N(r[4]) : '–') + '</td></tr>').join('') + '</table>' : '<div class="muted">No intake yet today.</div>';
  }

  // ── health ─────────────────────────────────────────────────────────────────
  function renderHealth() {
    const lv = state.live;
    if (!lv) { tiles('health-tiles', [['Live snapshot', 'none', '']]); return; }
    const h = lv.health;
    const up = h.uptimeS > 86400 ? (h.uptimeS / 86400).toFixed(1) + ' d' : (h.uptimeS / 3600).toFixed(1) + ' h';
    tiles('health-tiles', [
      ['Heap used', N(h.heapUsedMb) + ' MB', 'of ' + N(h.heapMaxMb) + ' MB · non-heap ' + N(h.nonHeapMb) + ' MB'],
      ['Process CPU', h.cpuPct != null ? h.cpuPct + '%' : '–', 'load ' + (h.load != null ? h.load : '–') + ' · ' + h.threads + ' threads'],
      ['Bot tick work', h.tickMsPerS != null ? N(h.tickMsPerS) + ' ms/s' : '–', h.buckets ? Object.entries(h.buckets).map(([k, v]) => k.toLowerCase() + ' ' + v).join(' · ') : ''],
      ['DB pool', h.pool ? h.pool.active + ' / ' + h.pool.total : '–', h.pool ? h.pool.idle + ' idle · ' + h.pool.waiting + ' waiting' : 'not up'],
      ['Bot DB permits', h.gate ? (h.gate.permits - h.gate.free) + ' / ' + h.gate.permits : '–', h.gate ? h.gate.overruns + ' waits gave up' : ''],
      ['GC', h.gcPerMin != null ? h.gcPerMin + '/min' : '–', h.gcMsPerMin != null ? h.gcMsPerMin + ' ms/min' : ''],
      ['Uptime', up, 'snapshot took ' + lv.exportMs + ' ms'],
    ]);
    const pts = points();
    Charts.line($('health-tick'), { x: 'time', series: [{ name: 'tick', color: C('--s1'), points: pts.map(p => [p.t, p.tick]) }] });
    Charts.line($('health-heap'), { x: 'time', series: [{ name: 'heap', color: C('--s7'), points: pts.map(p => [p.t, p.heap]) }] });
    Charts.line($('health-cpu'), { x: 'time', series: [{ name: 'cpu', color: C('--s2'), points: pts.map(p => [p.t, p.cpu]) }] });
    Charts.line($('health-db'), { x: 'time', series: [
      { name: 'pool connections in use', color: C('--s3'), points: pts.map(p => [p.t, p.pool]) },
      { name: 'bot permits in use', color: C('--s4'), points: pts.map(p => [p.t, p.gate]) },
    ] });
    const ph = (h.phases || []).slice().sort((a, b) => b[3] - a[3]);
    $('health-phases').innerHTML = ph.length ? '<table class="t"><tr><th>Phase</th><th>bucket</th><th class="num">ms/s</th><th class="num">calls/s</th><th class="num">µs/call</th></tr>' +
      ph.map(p => '<tr><td>' + E(p[0]) + (p[2] ? ' <small>(inside another)</small>' : '') + '</td><td>' + E(p[1].toLowerCase()) + '</td><td class="num">' + p[3] +
        '</td><td class="num">' + N(p[4]) + '</td><td class="num">' + N(p[5]) + '</td></tr>').join('') + '</table>'
      : '<div class="muted">Needs two snapshots.</div>';
    renderChat(lv.chat);
    renderChatChart(pts);
  }

  // Replies per hour by tier from the cumulative per-point counts (p.ch); a drop means the server restarted.
  function renderChatChart(pts) {
    const withCh = pts.filter(p => Array.isArray(p.ch));
    const host = $('health-chat-chart');
    if (withCh.length < 2) {
      Charts.line(host, { x: 'time', series: [], empty: withCh.length ? 'needs two snapshots' : 'this server build does not export chat counts per point yet' });
      return;
    }
    const hours = new Map();
    for (let i = 1; i < withCh.length; i++) {
      const a = withCh[i - 1].ch, b = withCh[i].ch;
      const h = Math.floor(withCh[i].t / 3600e3) * 3600e3;
      const row = hours.get(h) || [0, 0, 0, 0, 0];
      for (let k = 0; k < 5; k++) row[k] += b[k] >= a[k] ? b[k] - a[k] : b[k];
      hours.set(h, row);
    }
    const xs = [...hours.keys()].sort((x, y) => x - y);
    Charts.line(host, { x: 'time', series: CHAT_TIERS.map((k, i) => ({ name: k, color: C(SLOTS[i]),
      points: xs.map(h => [h + 1800e3, hours.get(h)[i]]) })), empty: 'no replies today' });
  }

  // ── GM audit (ticket 43), exported by slums-dashboard-extras from slumsms-gm-audit --json ──
  ['gm-who', 'gm-cmd', 'gm-outcome'].forEach(id => $(id).addEventListener('input', () => renderGm()));
  function renderGm() {
    const g = state.gm;
    if (!g) {
      $('gm-source').innerHTML = '<b>No GM audit export.</b> Install the slums-dashboard-extras timer with GM_CONTAINER and GM_SCHEMA set ' +
        '(it runs <code>slumsms-gm-audit --json</code> on the host; the browser never talks to the database).';
      tiles('gm-tiles', []); $('gm-commands').innerHTML = ''; $('gm-changes').innerHTML = '';
      Charts.bars($('gm-days'), { rows: [] }); Charts.bars($('gm-top'), { rows: [] });
      return;
    }
    const age = Date.now() / 1000 - g.at;
    $('gm-source').innerHTML = 'Schema <b>' + E(g.schema) + '</b> · exported ' + ago(age) + ' ago · newest ' + g.commands.length + ' commands and ' +
      g.changes.length + ' level changes' + (g.error ? '<br><span class="out-error">' + E(g.error) + '</span>' : '');
    const all = g.commands;
    const now = Date.now();
    const ts = r => Date.parse(String(r.at).replace(' ', 'T') + 'Z');
    const day = all.filter(r => now - ts(r) < 86400e3);
    const gms = new Set(day.map(r => r.character_name));
    tiles('gm-tiles', [
      ['Commands, last 24 h', N(day.length), gms.size + ' GM character' + (gms.size === 1 ? '' : 's')],
      ['Denied, last 24 h', N(day.filter(r => r.outcome === 'denied').length), 'GM level below the command rank'],
      ['Errors, last 24 h', N(day.filter(r => r.outcome === 'error').length), 'the command threw'],
      ['Level changes', N(g.changes.length), g.changes[0] ? 'latest ' + E(g.changes[0].at.slice(0, 16)) : 'none recorded'],
    ]);
    const who = $('gm-who').value.trim().toLowerCase(), cmd = $('gm-cmd').value.trim().toLowerCase(), out = $('gm-outcome').value;
    const rows = all.filter(r => (!who || (r.character_name + ' ' + r.account_name).toLowerCase().includes(who)) &&
      (!cmd || String(r.command).toLowerCase().includes(cmd)) && (!out || r.outcome === out));
    $('gm-commands').innerHTML = rows.length ? '<table class="t"><tr><th>When</th><th>Character</th><th>Account</th><th class="num">GM</th><th>Command</th><th>Args</th>' +
      '<th class="num">rank</th><th>Map</th><th class="num">ch</th><th>IP</th><th>Outcome</th></tr>' + rows.slice(0, 300).map(r =>
        '<tr><td>' + E(String(r.at).slice(0, 19)) + '</td><td>' + E(r.character_name) + '</td><td>' + E(r.account_name) + '</td><td class="num">' + E(r.gm_level) +
        '</td><td>' + E(r.command) + '</td><td class="wrap">' + E(r.args) + '</td><td class="num">' + E(r.command_rank) + '</td><td>' +
        (+r.map_id ? '<a href="#map/' + (+r.map_id) + '" title="' + E(mapName(+r.map_id)) + '">' + (+r.map_id) + '</a>' : '') + '</td><td class="num">' + E(r.channel) +
        '</td><td>' + E(r.ip) + '</td><td class="out-' + E(r.outcome) + '">' + E(r.outcome) + '</td></tr>').join('') + '</table>' +
        (rows.length > 300 ? '<div class="muted">Showing 300 of ' + rows.length + '.</div>' : '')
      : '<div class="muted">' + (all.length ? 'No command matches the filter.' : 'No GM commands recorded.') + '</div>';
    const perDay = {};
    all.forEach(r => { const d = String(r.at).slice(0, 10); perDay[d] = (perDay[d] || 0) + 1; });
    Charts.bars($('gm-days'), { rows: Object.entries(perDay).sort().slice(-30).map(([d, v]) => [d.slice(5), v]), valueName: 'commands', color: C('--s7'), empty: 'no GM commands' });
    const perCmd = {};
    all.forEach(r => { perCmd[r.command] = (perCmd[r.command] || 0) + 1; });
    Charts.bars($('gm-top'), { horizontal: true, rows: Object.entries(perCmd).sort((a, b) => b[1] - a[1]).slice(0, 10), valueName: 'uses', color: C('--s7'), empty: 'no GM commands' });
    $('gm-changes').innerHTML = g.changes.length ? '<table class="t"><tr><th>When</th><th>Account</th><th>Old level(s)</th><th class="num">New</th>' +
      '<th class="num">chars</th><th>Changed by</th><th>Note</th></tr>' + g.changes.map(r => '<tr><td>' + E(String(r.at).slice(0, 19)) + '</td><td>' +
        E(r.account_name) + ' <small>#' + E(r.account_id) + '</small></td><td>' + E(r.old_levels) + '</td><td class="num">' + E(r.new_level) + '</td><td class="num">' +
        E(r.characters) + '</td><td>' + E(r.changed_by) + '</td><td class="wrap">' + E(r.note) + '</td></tr>').join('') + '</table>'
      : '<div class="muted">No GM level changes recorded.</div>';
  }

  function renderChat(c) {
    if (!c) { $('health-chat').innerHTML = '<div class="muted">No chat numbers in this snapshot.</div>'; return; }
    const tiers = ['llm', 'scripted', 'emote', 'ignored', 'flagged'];
    const row = (name, t) => '<tr><td>' + name + '</td>' + tiers.map(k => {
      const v = (t || {})[k] || { n: 0, avgMs: 0 };
      return '<td class="num">' + N(v.n) + (v.n ? ' <small>' + N(v.avgMs) + ' ms</small>' : '') + '</td>';
    }).join('') + '</tr>';
    const kv = o => Object.entries(o || {}).map(([k, v]) => E(k) + ' ' + N(v)).join(' · ') || 'none';
    const d = c.decide || {};
    $('health-chat').innerHTML = '<table class="t"><tr><th></th>' + tiers.map(k => '<th class="num">' + k + '</th>').join('') + '</tr>' +
      row('since start', c.tiers) + row('this hour', c.thisHour) + row('last hour', c.lastHour) + '</table>' +
      '<div class="muted">Flags: ' + kv(c.flags) + '<br>Actions: ' + kv(c.actions) +
      '<br>Decide: ' + N(d.calls || 0) + ' calls, avg ' + N(d.avgMs || 0) + ' ms, ' + N(d.failed || 0) + ' failed, ' +
      N(d.cacheHits || 0) + ' cached, ' + N(d.rateLimited || 0) + ' rate limited' +
      (c.llm ? '<br>LLM: ' + E(c.llm) : '') + '</div>';
  }

  // ── refresh loop ───────────────────────────────────────────────────────────
  async function refreshLive() {
    try { state.live = await getJSON('live.json', true); } catch (e) { state.live = null; }
    renderStatus();
    try { state.ops = await getJSON('ops/status.json', true); } catch (e) { state.ops = null; }
    renderOps();
    try { state.gm = await getJSON('ops/gm.json', true); } catch (e) { state.gm = null; }
  }
  async function refreshAll() {
    await refreshLive();
    await loadDays();
    await loadDailies();
    render();
  }

  buildToggles();
  (async () => {
    await refreshAll();
    await loadLearning();
    await ensureIndex();
    route();
    if (!state.mapId) search();
    setInterval(async () => { await refreshLive(); if (state.tab !== 'map') render(); }, 60_000);
    setInterval(async () => { await loadDays(); await loadDailies(); if (state.tab !== 'map') render(); }, 300_000);
    setInterval(async () => { const idx = state.index; state.index = null; await ensureIndex(); if (!state.index.size) state.index = idx; await loadLearning(); if (state.tab === 'learning') render(); }, 900_000);
    setInterval(() => { renderStatus(); renderOps(); }, 15_000);
  })();
})();
