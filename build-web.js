// Inlines web/engine.js and web/ui.js into web/index.html and writes the single-file game
// to the Android assets folder (and dist/ for browser use). Run: node build-web.js
const fs = require('fs');
const read = f => fs.readFileSync(f, 'utf8');
const html = read('web/index.html');
const out = html
  .replace('<script src="engine.js"></script>', () => '<script>\n' + read('web/engine.js') + '\n</script>')
  .replace('<script src="ui.js"></script>', () => '<script>\n' + read('web/ui.js') + '\n</script>');
fs.mkdirSync('dist', { recursive: true });
fs.mkdirSync('app/src/main/assets', { recursive: true });
fs.writeFileSync('dist/gridbloom.html', out);
fs.writeFileSync('app/src/main/assets/gridbloom.html', out);
console.log('built', (out.length / 1024).toFixed(1) + ' KB -> dist/gridbloom.html and app/src/main/assets/gridbloom.html');
