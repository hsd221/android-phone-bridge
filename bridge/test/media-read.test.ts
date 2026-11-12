import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { createMcpServer } from '../src/mcp.js';
import { isOperation, toolInputs } from '../src/protocol.js';

const uri = 'content://media/external/images/media/123';

test('binary media has a bounded canonical URI contract and a separate read grant', () => {
  assert.deepEqual(toolInputs.phone_media_read.parse({ uri }), { uri, maxBytes: 1048576 });
  assert.equal(isOperation('phone_media_read'), false);
  for (const maxBytes of [1, 1048576, 4194304]) assert(toolInputs.phone_media_read.safeParse({ uri, maxBytes }).success);
  for (const maxBytes of [0, -1, 4194305, 1.2, '100']) assert(!toolInputs.phone_media_read.safeParse({ uri, maxBytes }).success);
  for (const invalid of ['file:///sdcard/x.png', 'content://other/external/images/media/1',
    'content://media/internal/images/media/1', 'content://media/external_primary/images/media/1',
    'content://media/external/images/media/0', 'content://media/external/images/media/01',
    `${uri}?download=1`, `${uri}#fragment`, `${uri}/`, 'content://media/external/images/media/%31',
    'content://media/external/images/media/9223372036854775808', 'content://media/external/videos/media/1', `${uri}\n`]) {
    assert(!toolInputs.phone_media_read.safeParse({ uri: invalid }).success, invalid);
  }
  assert(toolInputs.phone_media_read.safeParse({ uri: 'content://media/external/video/media/9' }).success);
  assert(!toolInputs.phone_media_read.safeParse({ uri, force: true }).success);
  assert(!toolInputs.phone_clipboard.safeParse({ coerceUri: true }).success);
});

test('media MCP content preserves bytes exactly once for common images and other media resources', async () => {
  let mimeType = 'image/png';
  let fail = false;
  const base64 = Buffer.from('synthetic binary fixture').toString('base64');
  const server = createMcpServer(async () => fail
    ? { ok: false, error: { code: 'MEDIA_TOO_LARGE', message: 'Synthetic size refusal' } }
    : { ok: true, data: { uri, mimeType, name: 'synthetic', sizeBytes: 24, base64, capturedAt: 1 } });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: 'media-contract-test', version: '1' });
  try {
    await server.connect(serverTransport);
    await client.connect(clientTransport);
    const tool = (await client.listTools()).tools.find(item => item.name === 'phone_media_read');
    assert.equal(tool?.annotations?.readOnlyHint, true);
    assert.equal(tool?.annotations?.destructiveHint, false);
    for (const mime of ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/heic', 'image/avif', 'video/mp4']) {
      mimeType = mime;
      const result = await client.callTool({ name: 'phone_media_read', arguments: { uri } });
      assert.equal(result.isError, false);
      const content = result.content as any[];
      const image = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(mime);
      assert.equal(content[0].type, image ? 'image' : 'resource');
      assert.equal(image ? content[0].data : content[0].resource.blob, base64);
      assert.equal(image ? content[0].mimeType : content[0].resource.mimeType, mime);
      const metadata = JSON.parse(content[1].text);
      assert.equal(metadata.data.uri, uri);
      assert.equal(Object.hasOwn(metadata.data, 'base64'), false);
      assert(!content[1].text.includes(base64));
    }
    fail = true;
    const failure = await client.callTool({ name: 'phone_media_read', arguments: { uri } });
    assert.equal(failure.isError, true);
    assert.equal((failure.content as any[])[0].type, 'text');
    assert.equal(JSON.parse((failure.content as any[])[0].text).error.code, 'MEDIA_TOO_LARGE');
  } finally { await client.close(); await server.close(); }
});
