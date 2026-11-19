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

function blockReply({ thinking, text, delay = 25, drop = false, holdAfter = 0 }) {
  return (res) => {
    sse(res, [
      'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_mock","model":"deepseek-flash"}}\n\n',
    ]);
    let i = 0;
    if (thinking) {
      res.write('event: content_block_start\ndata: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}\n\n');
      for (const ch of thinking) {
        res.write(`event: content_block_delta\ndata: ${JSON.stringify({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: ch } })}\n\n`);
      }
      res.write('event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n');
      i = 1;
    }
    res.write(`event: content_block_start\ndata: {"type":"content_block_start","index":${i},"content_block":{"type":"text","text":""}}\n\n`);
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
      if (holdAfter && n >= holdAfter) {
        // freeze mid-stream with the connection open — deterministic
        // "still streaming" state for screenshot comparison
        clearInterval(tick);
      }
    }, delay);
    res.on('close', () => clearInterval(tick));
  };
}

const SEARCH_SOURCES = [
  { url: 'https://www.example-a.com/deepseek-v3', title: 'DeepSeek-V3 Technical Report 详解：671B MoE 架构与创新', page_age: '2024-12-27T08:00:00Z', site_name: 'Example A', site_icon: null, snippet: 'DeepSeek-V3 采用混合专家架构，总参数量达到 671B，每个 token 激活 37B 参数。其创新的多头潜注意力（MLA）机制显著降低了推理时的 KV 缓存开销。' },
  { url: 'https://www.example-b.net/news/deepseek', title: 'DeepSeek 发布新一代推理模型，开源权重引发关注', page_age: '2025-01-15T12:30:00Z', site_name: 'Example B', site_icon: null, snippet: 'DeepSeek 团队最新发布的模型在多项基准测试中表现亮眼，开源策略受到社区广泛好评，被认为推动了开源大模型生态的发展。' },
  { url: 'https://www.example-c.org/blog/moe', title: '从 DeepSeek 看混合专家模型的工程化实践', page_age: null, site_name: null, site_icon: null, snippet: '混合专家模型通过路由机制只激活部分参数，在保持模型容量的同时大幅降低计算成本，已成为大模型架构的主流选择之一。' },
];

function searchReply(res) {
  sse(res, [
    'event: message_start\ndata: {"type":"message_start","message":{"id":"msg_mock","model":"deepseek-flash"}}\n\n',
  ]);
  res.write('event: content_block_start\ndata: ' + JSON.stringify({
    type: 'content_block_start', index: 0,
    content_block: { type: 'server_tool_use', id: 'toolu_search_1', name: 'deepseek_web_search', kind: 'search', input: { action: 'search', queries: ['DeepSeek'] } },
  }) + '\n\n');
  res.write('event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\n');
  res.write('event: content_block_start\ndata: ' + JSON.stringify({
    type: 'content_block_start', index: 1,
    content_block: { type: 'web_search_tool_result', tool_use_id: 'toolu_search_1', kind: 'search', content: SEARCH_SOURCES.map((s) => ({ type: 'web_search_result', ...s })) },
  }) + '\n\n');
  res.write('event: content_block_stop\ndata: {"type":"content_block_stop","index":1}\n\n');
  const TEXT = '根据搜索结果，DeepSeek-V3 采用了混合专家架构，总参数量 671B。其 MLA 机制有效降低了推理开销';
  res.write('event: content_block_start\ndata: ' + JSON.stringify({
    type: 'content_block_start', index: 2, content_block: { type: 'text', text: '' },
  }) + '\n\n');
  let n = 0;
  const chars = [...TEXT];
  const citations = [{ url: SEARCH_SOURCES[0].url, title: SEARCH_SOURCES[0].title }, { url: SEARCH_SOURCES[1].url, title: SEARCH_SOURCES[1].title }];
  const tick = setInterval(() => {
    if (n >= chars.length) {
      clearInterval(tick);
      for (const c of citations) {
        res.write('event: content_block_delta\ndata: ' + JSON.stringify({ type: 'content_block_delta', index: 2, delta: { type: 'citations_delta', citation: c } }) + '\n\n');
      }
      res.write('event: content_block_stop\ndata: {"type":"content_block_stop","index":2}\n\n');
      res.write('event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn","title":"搜索"},"usage":{"output_tokens":64}}\n\n');
      res.write('event: message_stop\ndata: {"type":"message_stop"}\n\n');
      res.end();
      return;
    }
    const ch = chars.slice(n, n + 2).join('');
    n += 2;
    res.write('event: content_block_delta\ndata: ' + JSON.stringify({ type: 'content_block_delta', index: 2, delta: { type: 'text_delta', text: ch } }) + '\n\n');
  }, 15);
  res.on('close', () => clearInterval(tick));
}

let globalDelayMs = 0;
const server = http.createServer((req, res) => {
  // runtime delay toggle for capturing transient UI states (e.g. "verifying")
  const setMatch = /^\/__set_delay\?ms=(\d+)/.exec(req.url || '');
  if (setMatch) {
    globalDelayMs = Number(setMatch[1]);
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
    return;
  }
  const delayMatch = /[?&]__delay=(\d+)/.exec(req.url || '');
  if (delayMatch) {
    req.url = req.url.replace(/[?&]__delay=\d+/, '').replace(/[?&]$/, '') || '/';
    return setTimeout(() => handle(req, res), Number(delayMatch[1]));
  }
  if (globalDelayMs) return setTimeout(() => handle(req, res), globalDelayMs);
  handle(req, res);
});
function handle(req, res) {
  let key = req.headers['x-api-key'] || '';
  const auth = req.headers['authorization'] || '';
  if (!key && /^Bearer\s+/i.test(auth)) key = auth.replace(/^Bearer\s+/i, '');
  if (req.method === 'GET' && req.url.endsWith('/models')) {
    return key === TEST_KEY
      ? send(res, 200, { data: [{ id: 'deepseek-flash' }] })
      : send(res, 401, { error: { message: 'Invalid API key' } });
  }
  if (req.method === 'POST' && /\/tools\/asr$/.test(req.url)) {
    // ASR stub: multipart body ignored, fixed transcript returned.
    req.resume();
    return key === TEST_KEY
      ? send(res, 200, { text: '语音输入测试' })
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
        const m = /^(thinking|long|slow|interrupted|401|402|429|500|refused|hold|search)$/i.exec(String(text).trim());
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
        case 'hold': return blockReply({ text: REPLY, delay: 60, holdAfter: REPLY.indexOf('- 列表项一') - 2 })(res);
        case 'interrupted': return blockReply({ text: REPLY, drop: true })(res);
        case 'search': return searchReply(res);
        case 'refused':
          return blockReply({ text: '你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。' })(res);
        default: return blockReply({ text: REPLY })(res);
      }
    });
    return;
  }
  send(res, 404, { error: { message: 'not found' } });
}

server.listen(PORT, () => console.log(`mock-api on :${PORT}, key=${TEST_KEY}`));
