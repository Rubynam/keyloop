#!/usr/bin/env python3
"""Generate viewer.html: every *.mermaid file under docs/diagrams, rendered with pan & zoom.

Run after editing any .mermaid file:  python3 docs/diagrams/build_viewer.py
Then open docs/diagrams/viewer.html in a browser (IntelliJ: right-click → Open In → Browser).
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).parent
GROUP_TITLES = {"roadmap": "Roadmap", "hld": "High-Level Design", "dld": "Detail-Level Design"}

diagrams = []
for group in GROUP_TITLES:
    for f in sorted((ROOT / group).glob("*.mermaid")):
        diagrams.append({"id": f"{group}/{f.stem}", "group": GROUP_TITLES[group],
                         "name": f.stem, "src": f.read_text()})

# "</" would close the <script> tag early; JSON allows the escaped form.
data = json.dumps(diagrams).replace("</", "<\\/")

HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Scheduler Diagrams</title>
<style>
  :root { --bg:#ffffff; --panel:#f5f6f8; --text:#1f2328; --muted:#656d76; --accent:#0969da; --border:#d0d7de; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#1e1f22; --panel:#2b2d30; --text:#dfe1e5; --muted:#8c8f94; --accent:#548af7; --border:#393b40; }
  }
  * { box-sizing: border-box; }
  body { margin:0; height:100vh; display:flex; font:14px/1.4 system-ui, sans-serif; background:var(--bg); color:var(--text); }
  nav { width:280px; flex-shrink:0; overflow:auto; background:var(--panel); border-right:1px solid var(--border); padding:12px 0; }
  nav h2 { font-size:11px; text-transform:uppercase; letter-spacing:.06em; color:var(--muted); margin:16px 16px 6px; }
  nav a { display:block; padding:5px 16px; color:var(--text); text-decoration:none; font-family:ui-monospace, monospace; font-size:12.5px; }
  nav a:hover { background:var(--border); }
  nav a.active { color:var(--accent); font-weight:600; border-left:3px solid var(--accent); padding-left:13px; }
  main { flex:1; display:flex; flex-direction:column; min-width:0; }
  header { display:flex; align-items:center; gap:8px; padding:8px 12px; border-bottom:1px solid var(--border); }
  header strong { flex:1; font-family:ui-monospace, monospace; }
  header span { color:var(--muted); font-size:12px; }
  button { background:var(--panel); color:var(--text); border:1px solid var(--border); border-radius:6px; padding:4px 10px; cursor:pointer; font:inherit; }
  button:hover { border-color:var(--accent); }
  #stage { flex:1; overflow:hidden; cursor:grab; }
  #stage:active { cursor:grabbing; }
  #stage svg { width:100%; height:100%; max-width:none !important; }
  #error { color:#cf222e; white-space:pre-wrap; padding:16px; font-family:ui-monospace, monospace; }
  @media (max-width: 700px) { body { flex-direction:column; } nav { width:100%; max-height:35vh; } }
</style>
</head>
<body>
<nav id="nav"></nav>
<main>
  <header>
    <strong id="title"></strong>
    <span>scroll = zoom · drag = pan</span>
    <button id="zoomIn">+</button>
    <button id="zoomOut">−</button>
    <button id="fit">Fit</button>
  </header>
  <div id="stage"></div>
</main>
<script id="data" type="application/json">__DATA__</script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/svg-pan-zoom@3.6.1/dist/svg-pan-zoom.min.js"></script>
<script>
  const diagrams = JSON.parse(document.getElementById('data').textContent);
  const dark = matchMedia('(prefers-color-scheme: dark)').matches;
  mermaid.initialize({ startOnLoad: false, theme: dark ? 'dark' : 'default', securityLevel: 'loose' });

  const nav = document.getElementById('nav');
  let lastGroup = null;
  for (const d of diagrams) {
    if (d.group !== lastGroup) {
      nav.insertAdjacentHTML('beforeend', `<h2>${d.group}</h2>`);
      lastGroup = d.group;
    }
    const a = document.createElement('a');
    a.href = '#' + d.id; a.textContent = d.name; a.dataset.id = d.id;
    a.onclick = e => {
      e.preventDefault();
      try { history.replaceState(null, '', '#' + d.id); } catch (_) {}
      show(d.id);
    };
    nav.appendChild(a);
  }

  let panZoom = null, seq = 0;
  async function show(id) {
    const d = diagrams.find(x => x.id === id) || diagrams[0];
    document.getElementById('title').textContent = d.id + '.mermaid';
    document.querySelectorAll('nav a').forEach(a => a.classList.toggle('active', a.dataset.id === d.id));
    const stage = document.getElementById('stage');
    if (panZoom) { panZoom.destroy(); panZoom = null; }
    try {
      const { svg } = await mermaid.render('m' + (++seq), d.src);
      stage.innerHTML = svg;
      const el = stage.querySelector('svg');
      el.removeAttribute('width'); el.removeAttribute('height'); el.style.maxWidth = 'none';
      panZoom = svgPanZoom(el, { zoomScaleSensitivity: 0.3, minZoom: 0.2, maxZoom: 20, fit: true, center: true });
    } catch (e) {
      stage.innerHTML = '<div id="error"></div>';
      document.getElementById('error').textContent = 'Mermaid error in ' + d.id + '.mermaid\\n\\n' + e.message;
    }
  }

  document.getElementById('zoomIn').onclick = () => panZoom && panZoom.zoomIn();
  document.getElementById('zoomOut').onclick = () => panZoom && panZoom.zoomOut();
  document.getElementById('fit').onclick = () => panZoom && (panZoom.resize(), panZoom.fit(), panZoom.center());
  addEventListener('resize', () => panZoom && (panZoom.resize(), panZoom.fit(), panZoom.center()));
  addEventListener('hashchange', () => show(location.hash.slice(1)));
  show(location.hash.slice(1));
</script>
</body>
</html>
"""

(ROOT / "viewer.html").write_text(HTML.replace("__DATA__", data))
print(f"viewer.html: {len(diagrams)} diagrams")
