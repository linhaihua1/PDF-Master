#!/usr/bin/env node
/**
 * 最小 Stirling-PDF 替身，用于在没有 Java 环境时验证网关链路。
 * 实现三个端点：状态、合并、压缩、页数分析。
 * 输出是结构合法的最小 PDF（可被阅读器打开），便于断言。
 */
import { createServer } from 'node:http';

const PORT = Number(process.env.MOCK_PORT ?? 8080);

function minimalPdf(pages = 1, label = 'pdf-gateway test') {
  const objects = [];
  const content = `BT /F1 18 Tf 72 720 Td (${label}) Tj ET`;
  const kids = [];
  let objNo = 3;
  for (let i = 0; i < pages; i += 1) {
    kids.push(`${objNo} 0 R`);
    objects.push(`${objNo} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 ${objNo + 2} 0 R >> >> /Contents ${objNo + 1} 0 R >>\nendobj`);
    const stream = `${content}\n`;
    objects.push(`${objNo + 1} 0 obj\n<< /Length ${stream.length} >>\nstream\n${stream}endstream\nendobj`);
    objects.push(`${objNo + 2} 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj`);
    objNo += 3;
  }
  const header = '%PDF-1.4\n';
  const body = [
    `1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj`,
    `2 0 obj\n<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pages} >>\nendobj`,
    ...objects,
  ].join('\n');
  let offset = header.length;
  const xref = ['xref', `0 ${objNo}`, '0000000000 65535 f '];
  let cursor = header.length;
  for (const chunk of body.split('\nendobj\n')) {
    xref.push(`${String(cursor).padStart(10, '0')} 00000 n `);
    cursor += chunk.length + '\nendobj\n'.length;
  }
  const tail = `\ntrailer\n<< /Size ${objNo} /Root 1 0 R >>\nstartxref\n${offset}\n%%EOF\n`;
  return Buffer.from(header + body + tail, 'latin1');
}

function readBody(req) {
  return new Promise(done => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => done(Buffer.concat(chunks)));
  });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://127.0.0.1:${PORT}`);
  if (url.pathname === '/api/v1/info/status') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ status: 'UP', version: 'mock-3.3.0' }));
  }

  const body = await readBody(req);
  const size = body.length;

  if (url.pathname === '/api/v1/analysis/page-count') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ pageCount: 1, receivedBytes: size }));
  }

  if (url.pathname.startsWith('/api/v1/')) {
    const pdf = minimalPdf(1, `mock ${url.pathname} ${(size / 1024).toFixed(1)}KB`);
    res.writeHead(200, {
      'content-type': 'application/pdf',
      'content-disposition': `attachment; filename="result.pdf"`,
      'content-length': pdf.length,
    });
    return res.end(pdf);
  }

  res.writeHead(404, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found', path: url.pathname }));
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mock Stirling-PDF http://127.0.0.1:${PORT}`);
});
