// 从 lucide-static 生成 MovoIcons.kt：只保留几何，线宽由 MovoIcon 按尺寸配对（规范第 6 章）。
import fs from 'node:fs';
const [,, iconDir, outFile, ...names] = process.argv;
const num = (v) => Number(v);
function toPath(tag, a) {
  switch (tag) {
    case 'path': return a.d;
    case 'line': return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`;
    case 'polyline': case 'polygon': {
      const p = a.points.trim().split(/[\s,]+/).map(Number); let d = `M${p[0]} ${p[1]}`;
      for (let i = 2; i < p.length; i += 2) d += `L${p[i]} ${p[i+1]}`;
      return tag === 'polygon' ? d + 'Z' : d;
    }
    case 'circle': { const cx = num(a.cx), cy = num(a.cy), r = num(a.r);
      return `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2*r} 0a${r} ${r} 0 1 0 ${-2*r} 0`; }
    case 'ellipse': { const cx = num(a.cx), cy = num(a.cy), rx = num(a.rx), ry = num(a.ry);
      return `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2*rx} 0a${rx} ${ry} 0 1 0 ${-2*rx} 0`; }
    case 'rect': { const x = num(a.x||0), y = num(a.y||0), w = num(a.width), h = num(a.height); const r = Math.min(num(a.rx||a.ry||0), w/2, h/2);
      if (!r) return `M${x} ${y}h${w}v${h}h${-w}Z`;
      return `M${x+r} ${y}h${w-2*r}a${r} ${r} 0 0 1 ${r} ${r}v${h-2*r}a${r} ${r} 0 0 1 ${-r} ${r}h${-(w-2*r)}a${r} ${r} 0 0 1 ${-r} ${-r}v${-(h-2*r)}a${r} ${r} 0 0 1 ${r} ${-r}Z`; }
  }
  throw new Error('unsupported ' + tag);
}
const camel = (s) => s.replace(/-([a-z0-9])/g, (_, c) => c.toUpperCase()).replace(/^./, (c) => c.toUpperCase());
let body = '';
for (const name of names) {
  const svg = fs.readFileSync(`${iconDir}/${name}.svg`, 'utf8');
  const paths = [];
  for (const m of svg.matchAll(/<(path|line|polyline|polygon|circle|ellipse|rect)\s([^>]*?)\/?>/g)) {
    const attrs = {}; for (const am of m[2].matchAll(/([a-z0-9-]+)="([^"]*)"/g)) attrs[am[1]] = am[2];
    paths.push(toPath(m[1], attrs));
  }
  body += `    /** lucide:${name} */\n    val ${camel(name)} = MovoIconData("${name}", listOf(\n${paths.map(p => `        "${p}",`).join('\n')}\n    ))\n`;
}
const out = `package io.github.mangi.eta.ui.theme

// 由 .docs/design-restore/tools/gen-icons.mjs 从 lucide-static 生成，不要手改；新增图标时重新生成。
// 设计稿图标均为 Lucide（ISC 许可），这里只保存 24 网格下的几何，线宽由 MovoIcon 按尺寸配对。

internal object MovoIcons {
${body}}
`;
fs.writeFileSync(outFile, out);
console.log('icons', names.length);
