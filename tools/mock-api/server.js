#!/usr/bin/env node
// Mock DeepSeek Anthropic-compatible streaming API. No deps.
// GET  /anthropic/v1/models -> 200 if x-api-key == TEST_KEY else 401
// POST /anthropic/v1/messages -> SSE stream; scenario via x-mock-scenario header
//   or last user message text: "thinking" | "long" | "slow" | "interrupted"
//   | "401" | "402" | "429" | "500" | "refused" (default: normal)
const http = require('http');

const PORT = process.env.PORT || 8321;
const TEST_KEY = process.env.MOCK_KEY || 'sk-mock-test-key';

const REPLY = `你好！这是一条来自假后端的流式回复。

它模拟 DeepSeek 官方接口的 SSE 增量输出，用来验证客户端的流式渲染。包含 **加粗**、*斜体* 和 \`inline code\`。

\`\`\`kotlin
fun main() {
    println("hello mock")
}
\`\`\`

- 列表项一
- 列表项二
- ~~删除线~~ ~~和~~ 正常

1. 有序一
2. 有序二

> 引用段落，用来检查块样式。

| 名称 | 值 |
| --- | --- |
| alpha | 1 |
| beta | 2 |

行内公式 $a^2+b^2=c^2$ 与：

$$\int_0^1 x^2\,dx = \frac{1}{3}$$

结束。`;

const THINKING = '用户在向我问好并测试流式输出。我应该用中文简短回应，展示一些 markdown 结构来验证渲染。首先确认网络连通，然后给出带格式的回复。';

const LONG = Array(60).fill('这是一段用于测试长回复滚动与渲染性能的文本。').join(' ');

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(body);
}

function sse(res, lines) {
  res.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
  });
  for (const l of lines) res.write(l);
}

function blockReply({ thinking, text, delay = 25, drop = false }) {
  return (res) => {
    sse(res, [
      'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_mock","model":"deepseek-flash"}}\n\n',
    ]);
    let i = 0;
    if (thinking) {
      res.write('event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}\n\n');
      for (const ch of thinking) {
        res.write(`event: content_block_delta\ndata: ${JSON.stringify({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: ch } })}\n\n`);
      }
      res.write('event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n');
      i = 1;
    }
    res.write(`event: content_block_start\ndata: {"type":"content_block_start","index":${i},"content_block":{"type":"text"}}\n\n`);
    const chars = [...text];
    const limit = drop ? Math.floor(chars.length / 2) : chars.length;
    let n = 0;
    const tick = setInterval(() => {
      if (n >= limit) {
        clearInterval(tick);
        if (!drop) {
          res.write(`event: content_block_stop\ndata: {"type":"content_block_stop","index":${i}}\n\n`);
          res.write('event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn","title":"问候"},"usage":{"output_tokens":42}}\n\n');
          res.write('event: message_stop\ndata: {"type":"message_stop"}\n\n');
        }
        res.end();
        return;
      }
      const ch = chars.slice(n, n + 2).join('');
      n += 2;
      res.write(`event: content_block_delta\ndata: ${JSON.stringify({ type: 'content_block_delta', index: i, delta: { type: 'text_delta', text: ch } })}\n\n`);
    }, delay);
    res.on('close', () => clearInterval(tick));
  };
}

const server = http.createServer((req, res) => {
  let key = req.headers['x-api-key'] || '';
  const auth = req.headers['authorization'] || '';
  if (!key && /^Bearer\s+/i.test(auth)) key = auth.replace(/^Bearer\s+/i, '');
  if (req.method === 'GET' && req.url.endsWith('/models')) {
    return key === TEST_KEY
      ? send(res, 200, { data: [{ id: 'deepseek-flash' }] })
      : send(res, 401, { error: { message: 'Invalid API key' } });
  }
  if (req.method === 'POST' && req.url.endsWith('/messages')) {
    let body = '';
    req.on('data', (d) => (body += d));
    req.on('end', () => {
      if (key !== TEST_KEY) return send(res, 401, { error: { message: 'Invalid API key' } });
      let scenario = req.headers['x-mock-scenario'] || '';
      try {
        const parsed = JSON.parse(body);
        const last = (parsed.messages || []).filter((m) => m.role === 'user').pop();
        const text = last && (last.content?.[0]?.text || last.content) || '';
        const m = /^(thinking|long|slow|interrupted|401|402|429|500|refused)$/i.exec(String(text).trim());
        if (m) scenario = m[1].toLowerCase();
      } catch (e) {}
      switch (scenario) {
        case '401': return send(res, 401, { error: { message: 'Invalid API key' } });
        case '402': return send(res, 402, { error: { message: 'Insufficient balance' } });
        case '429': return send(res, 429, { error: { message: 'Rate limit exceeded' } });
        case '500': return send(res, 500, { error: { message: 'Internal server error' } });
        case 'thinking': return blockReply({ thinking: THINKING, text: REPLY })(res);
        case 'long': return blockReply({ text: LONG, delay: 5 })(res);
        case 'slow': return blockReply({ text: REPLY, delay: 120 })(res);
        case 'interrupted': return blockReply({ text: REPLY, drop: true })(res);
        case 'refused':
          return blockReply({ text: '你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。' })(res);
        default: return blockReply({ text: REPLY })(res);
      }
    });
    return;
  }
  send(res, 404, { error: { message: 'not found' } });
});

server.listen(PORT, () => console.log(`mock-api on :${PORT}, key=${TEST_KEY}`));
