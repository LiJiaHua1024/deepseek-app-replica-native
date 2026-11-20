#!/usr/bin/env node
// Feeds one SSE sample through the reference client's OWN parser
// (/tmp/webview-reference/src/services/api/sse.ts via type stripping) and
// prints the decoded event sequence as JSON to stdout.
// usage: node --experimental-strip-types tools/parse_ref.mjs <sample.sse>
import { readFileSync } from 'node:fs';
import { ServerSentEventDecoder } from '/tmp/webview-reference/src/services/api/sse.ts';

const bytes = readFileSync(process.argv[2]);
const dec = new ServerSentEventDecoder();
const td = new TextDecoder();
// split the sample into two chunks at a mid-CR boundary to exercise the
// decoder's chunk-join behavior deterministically (same for both parsers)
const text = td.decode(bytes);
const cut = Math.floor(text.length * 0.6);
const events = [];
for (const e of dec.push(text.slice(0, cut))) events.push(e);
for (const e of dec.push(text.slice(cut))) events.push(e);
for (const e of dec.end()) events.push(e);
process.stdout.write(JSON.stringify(events.map((e) => ({ event: e.event, data: e.data }))) + '\n');
