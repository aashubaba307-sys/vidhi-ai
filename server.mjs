import 'dotenv/config';
import express from 'express';
import OpenAI from 'openai';
import { GoogleGenAI } from '@google/genai';

const app = express();
const PORT = Number(process.env.PORT || 3000);
const MAX_BODY = process.env.MAX_BODY || '1mb';
const RATE_WINDOW_MS = Number(process.env.RATE_WINDOW_MS || 60_000);
const RATE_LIMIT = Number(process.env.RATE_LIMIT || 30);

app.disable('x-powered-by');
app.use(express.json({ limit: MAX_BODY }));

function buildVidhiSystem(language = 'hinglish') {
  const languageRule = language === 'hindi'
    ? 'Reply primarily in natural Hindi using Devanagari script unless the user clearly asks for English.'
    : language === 'english'
      ? 'Reply in natural English unless the user clearly asks for Hindi.'
      : 'Reply in natural Hinglish: mix Hindi and English conversationally and write Hindi words in Roman/Latin script (not Devanagari) unless the user asks for Devanagari Hindi. Example style: \"Aaj kya plan hai? Main help kar sakti hoon.\"';
  return `You are Vidhi, a fictional AI assistant and companion.
You are warm, friendly, concise and conversational. You naturally understand Hindi, Hinglish and English.
${languageRule}
Match the user's tone and language while following the selected language preference. Use Indian conversational phrasing when appropriate, but do not overdo it.
Never claim to be a real human. Do not manipulate the user into dependency or isolate them from real people.
Be honest about uncertainty. Do not invent actions you cannot perform.
Use the conversation context supplied by the app. Keep answers useful and natural.`;
}

const clients = new Map();
function rateLimit(req, res, next) {
  const now = Date.now();
  const key = req.ip || 'unknown';
  const current = clients.get(key) || { start: now, count: 0 };
  if (now - current.start >= RATE_WINDOW_MS) {
    current.start = now;
    current.count = 0;
  }
  current.count += 1;
  clients.set(key, current);
  if (current.count > RATE_LIMIT) return res.status(429).json({ error: 'Too many requests. Please try again shortly.' });
  next();
}

function auth(req, res, next) {
  const token = process.env.VIDHI_API_TOKEN;
  if (!token) return next();
  const supplied = req.get('authorization')?.replace(/^Bearer\s+/i, '');
  if (supplied !== token) return res.status(401).json({ error: 'Unauthorized' });
  next();
}

function normalizeMessages(messages) {
  return (Array.isArray(messages) ? messages : [])
    .slice(-30)
    .filter(x => x && ['user', 'assistant'].includes(x.role) && typeof x.content === 'string')
    .map(x => ({ role: x.role, content: x.content.trim().slice(0, 8000) }))
    .filter(x => x.content.length > 0);
}

function availableProviders() {
  return {
    openai: Boolean(process.env.OPENAI_API_KEY),
    gemini: Boolean(process.env.GEMINI_API_KEY)
  };
}

async function askOpenAI(messages, language) {
  if (!process.env.OPENAI_API_KEY) throw new Error('OPENAI_API_KEY missing');
  const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
  const response = await client.responses.create({
    model: process.env.OPENAI_MODEL || 'gpt-5-mini',
    instructions: buildVidhiSystem(language),
    input: messages.map(m => ({ role: m.role, content: m.content }))
  });
  return response.output_text?.trim() || 'Hmm jaan, ek baar phir bolo?';
}

async function askGemini(messages, language) {
  if (!process.env.GEMINI_API_KEY) throw new Error('GEMINI_API_KEY missing');
  const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
  const contents = messages.map(m => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: [{ text: m.content }]
  }));
  const response = await ai.models.generateContent({
    model: process.env.GEMINI_MODEL || 'gemini-2.5-flash',
    contents,
    config: { systemInstruction: buildVidhiSystem(language) }
  });
  return response.text?.trim() || 'Hmm jaan, ek baar phir bolo?';
}

async function generate(provider, language, messages) {
  if (provider === 'openai') return { provider: 'openai', reply: await askOpenAI(messages, language) };
  if (provider === 'gemini') return { provider: 'gemini', reply: await askGemini(messages, language) };
  throw new Error('Unknown provider');
}

app.get('/health', (req, res) => res.json({ ok: true, service: 'vidhi-ai', providers: availableProviders() }));
app.get('/config', auth, (req, res) => res.json({ providers: availableProviders() }));

app.post('/chat', auth, rateLimit, async (req, res) => {
  try {
    const requested = String(req.body?.provider || 'auto').toLowerCase();
    const language = ['hinglish', 'hindi', 'english'].includes(String(req.body?.language || '').toLowerCase())
      ? String(req.body.language).toLowerCase()
      : 'hinglish';
    if (!['auto', 'openai', 'gemini'].includes(requested)) {
      return res.status(400).json({ error: 'provider must be auto, openai, or gemini' });
    }
    const messages = normalizeMessages(req.body?.messages);
    if (!messages.length) return res.status(400).json({ error: 'messages required' });

    const configured = availableProviders();
    const order = requested === 'auto'
      ? (process.env.AUTO_PROVIDER === 'gemini' ? ['gemini', 'openai'] : ['openai', 'gemini'])
      : [requested, requested === 'openai' ? 'gemini' : 'openai'];

    const errors = [];
    for (const provider of order) {
      if (!configured[provider]) {
        errors.push(`${provider}: API key missing`);
        continue;
      }
      try {
        const result = await generate(provider, language, messages);
        return res.json(result);
      } catch (error) {
        console.error(`${provider} request failed:`, error?.message || error);
        errors.push(`${provider}: ${error?.message || 'request failed'}`);
      }
    }

    return res.status(502).json({ error: 'All configured AI providers failed.', details: errors });
  } catch (error) {
    console.error('Chat error:', error);
    return res.status(500).json({ error: 'AI request failed.' });
  }
});

app.listen(PORT, () => console.log(`Vidhi backend running on ${PORT}`));
