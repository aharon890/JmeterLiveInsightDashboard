const { marked } = require('marked');
const fs = require('fs');
const path = require('path');

const mdPath = path.resolve(__dirname, '../../docs/Tutorial/01-Overview.md');
const outPath = path.resolve(__dirname, '../../docs/Tutorial/01-Overview-preview.html');
const md = fs.readFileSync(mdPath, 'utf8');
const html = marked.parse(md);

const page = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif; max-width: 980px; margin: 40px auto; padding: 0 20px; color: #24292f; line-height: 1.6; }
h1 { border-bottom: 1px solid #d0d7de; padding-bottom: 8px; }
h2 { border-bottom: 1px solid #d0d7de; padding-bottom: 6px; margin-top: 24px; }
h3 { margin-top: 24px; }
h4 { margin-top: 20px; }
table { border-collapse: collapse; width: 100%; margin: 16px 0; }
th, td { border: 1px solid #d0d7de; padding: 6px 13px; }
th { background: #f6f8fa; font-weight: 600; }
tr:nth-child(even) { background: #f6f8fa; }
code { background: #f6f8fa; padding: 2px 6px; border-radius: 4px; font-size: 85%; }
pre { background: #f6f8fa; padding: 16px; border-radius: 6px; overflow-x: auto; }
pre code { background: none; padding: 0; }
img { max-width: 100%; border: 1px solid #d0d7de; border-radius: 6px; margin: 8px 0; }
ul, ol { padding-left: 2em; }
strong { font-weight: 600; }
a { color: #0969da; text-decoration: none; }
</style>
</head>
<body>${html}</body>
</html>`;

fs.writeFileSync(outPath, page);
console.log('Preview: ' + outPath);
