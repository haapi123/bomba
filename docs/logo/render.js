// node render.js <svg> <png> <width> <height> [transparent]
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const [svgPath, pngPath, w, h, transparent] = process.argv.slice(2);
  const svg = fs.readFileSync(svgPath, 'utf8');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: 1 });
  await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace(/width="\d+" height="\d+" viewBox/, `width="${w}" height="${h}" viewBox`)}</body></html>`);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: pngPath, omitBackground: transparent === 'transparent', clip: { x: 0, y: 0, width: +w, height: +h } });
  await browser.close();
})();
