import test from 'node:test';
import assert from 'node:assert';
import { SseStreamParser, SseValidationError, SseParseError, SsePrematureEofError } from './sse-parser.ts';

test('SSE Parser - Basic clean event flow', () => {
  const events = [];
  const parser = new SseStreamParser({
    onEvent: (evt) => events.push(evt),
  });

  parser.feed('event: run.started\ndata: {"runId":"run-1","seq":1}\n\n');
  parser.feed('event: message.delta\ndata: {"runId":"run-1","seq":2,"delta":"Hello "}\n\n');
  parser.feed('event: run.completed\ndata: {"runId":"run-1","seq":3,"status":"COMPLETED"}\n\n');
  parser.finish();

  assert.strictEqual(events.length, 3);
  assert.strictEqual(events[0].event, 'run.started');
  assert.strictEqual(events[1].data.delta, 'Hello ');
  assert.strictEqual(events[2].event, 'run.completed');
  assert.strictEqual(parser.getTerminalCount(), 1);
});

test('SSE Parser - Byte-by-byte split across entire CRLF stream (including \\r and \\n split across chunks)', () => {
  const events = [];
  const parser = new SseStreamParser({
    onEvent: (evt) => events.push(evt),
  });

  const fullText = 'event: run.started\r\ndata: {"runId":"r-byte","seq":1}\r\n\r\n' +
    'event: message.delta\r\ndata: {"runId":"r-byte","seq":2,"delta":"Byte test"}\r\n\r\n' +
    'event: run.completed\r\ndata: {"runId":"r-byte","seq":3,"status":"COMPLETED"}\r\n\r\n';

  // Feed one character at a time to test every split boundary (including trailing \r before \n)
  for (let i = 0; i < fullText.length; i++) {
    parser.feed(fullText[i]);
  }
  parser.finish();

  assert.strictEqual(events.length, 3);
  assert.strictEqual(events[0].event, 'run.started');
  assert.strictEqual(events[1].data.delta, 'Byte test');
  assert.strictEqual(events[2].event, 'run.completed');
});

test('SSE Parser - Multi-byte UTF-8 Chinese characters split across byte chunks', () => {
  const events = [];
  const parser = new SseStreamParser({
    onEvent: (evt) => events.push(evt),
  });

  const encoder = new TextEncoder();
  const fullText = 'event: run.started\ndata: {"runId":"r-utf8","seq":1}\n\n' +
    'event: message.delta\ndata: {"runId":"r-utf8","seq":2,"delta":"繁體中文測試"}\n\n' +
    'event: run.completed\ndata: {"runId":"r-utf8","seq":3,"status":"COMPLETED"}\n\n';

  const fullBytes = encoder.encode(fullText);

  // Split into 3-byte chunks to intentionally slice UTF-8 code point boundaries
  const chunkSize = 3;
  for (let i = 0; i < fullBytes.length; i += chunkSize) {
    const chunk = fullBytes.subarray(i, Math.min(i + chunkSize, fullBytes.length));
    parser.feed(chunk);
  }
  parser.finish();

  assert.strictEqual(events.length, 3);
  assert.strictEqual(events[1].data.delta, '繁體中文測試');
});

test('SSE Parser - Heartbeat comment lines are ignored without incrementing seq', () => {
  const events = [];
  const parser = new SseStreamParser({
    onEvent: (evt) => events.push(evt),
  });

  parser.feed(': heartbeat\n\n');
  parser.feed('event: run.started\ndata: {"runId":"r-hb","seq":1}\n\n');
  parser.feed(': heartbeat\n\n');
  parser.feed(': ping\n\n');
  parser.feed('event: message.delta\ndata: {"runId":"r-hb","seq":2,"delta":"ok"}\n\n');
  parser.feed(': heartbeat\n\n');
  parser.feed('event: run.completed\ndata: {"runId":"r-hb","seq":3,"status":"COMPLETED"}\n\n');
  parser.finish();

  assert.strictEqual(events.length, 3);
  assert.strictEqual(events[0].event, 'run.started');
  assert.strictEqual(events[1].event, 'message.delta');
  assert.strictEqual(events[2].event, 'run.completed');
});

test('SSE Parser - Duplicate run.started must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  parser.feed('event: run.started\ndata: {"runId":"r-dup","seq":1}\n\n');
  assert.throws(
    () => parser.feed('event: run.started\ndata: {"runId":"r-dup","seq":1}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Duplicate run.started')
  );
});

test('SSE Parser - Blank or missing runId must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  assert.throws(
    () => parser.feed('event: run.started\ndata: {"runId":"  ","seq":1}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Missing or invalid runId')
  );
});

test('SSE Parser - Non-integer or negative seq must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  assert.throws(
    () => parser.feed('event: run.started\ndata: {"runId":"r-seq","seq":1.5}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Invalid seq')
  );

  const parser2 = new SseStreamParser({
    onEvent: () => {},
  });
  assert.throws(
    () => parser2.feed('event: run.started\ndata: {"runId":"r-seq","seq":-1}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Invalid seq')
  );
});

test('SSE Parser - Sequence gap must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  parser.feed('event: run.started\ndata: {"runId":"r-gap","seq":1}\n\n');
  assert.throws(
    () => parser.feed('event: message.delta\ndata: {"runId":"r-gap","seq":3,"delta":"gap!"}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Sequence gap')
  );
});

test('SSE Parser - Mismatched runId must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  parser.feed('event: run.started\ndata: {"runId":"r-expected","seq":1}\n\n');
  assert.throws(
    () => parser.feed('event: message.delta\ndata: {"runId":"r-wrong","seq":2,"delta":"diff"}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('Mismatched runId')
  );
});

test('SSE Parser - Any event after terminal event must be rejected', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  parser.feed('event: run.started\ndata: {"runId":"r-term","seq":1}\n\n');
  parser.feed('event: run.completed\ndata: {"runId":"r-term","seq":2,"status":"COMPLETED"}\n\n');

  assert.throws(
    () => parser.feed('event: message.delta\ndata: {"runId":"r-term","seq":3,"delta":"late delta"}\n\n'),
    (err) => err instanceof SseValidationError && err.message.includes('after terminal event')
  );
});

test('SSE Parser - Premature EOF before terminal throws SsePrematureEofError without dispatching truncated data', () => {
  let deltaReceived = false;
  const parser = new SseStreamParser({
    onEvent: (evt) => {
      if (evt.event === 'message.delta') deltaReceived = true;
    },
  });

  parser.feed('event: run.started\ndata: {"runId":"r-eof","seq":1}\n\n');
  // Incomplete frame truncated midway through without trailing double newline
  parser.feed('event: message.delta\ndata: {"runId":"r-eof","seq":2,"delta":"tru');

  assert.throws(
    () => parser.finish(),
    (err) => err instanceof SsePrematureEofError
  );
  // Unfinished frame must NOT have been dispatched
  assert.strictEqual(deltaReceived, false);
});

test('SSE Parser - Abnormal trailing content after terminal event throws SseValidationError', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  parser.feed('event: run.started\ndata: {"runId":"r-trail","seq":1}\n\n');
  parser.feed('event: run.completed\ndata: {"runId":"r-trail","seq":2,"status":"COMPLETED"}\n\n');
  // Trailing incomplete garbage
  parser.feed('some garbage text left over');

  assert.throws(
    () => parser.finish(),
    (err) => err instanceof SseValidationError && err.message.includes('Abnormal trailing content')
  );
});

test('SSE Parser - Corrupted JSON throws SseParseError', () => {
  const parser = new SseStreamParser({
    onEvent: () => {},
  });

  assert.throws(
    () => parser.feed('event: run.started\ndata: {unclosed json\n\n'),
    (err) => err instanceof SseParseError
  );
});
