import http from 'node:http';
import { randomBytes, timingSafeEqual, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const token = () => randomBytes(32).toString('base64url');
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' &&
  Buffer.byteLength(a) === Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const fail = (status, message) => Object.assign(new Error(message), { status });
const assets = new Map([
  ['/', ['text/html', readFileSync(new URL('public/index.html', import.meta.url))]],
  ['/viewer.js', ['text/javascript', readFileSync(new URL('public/viewer.js', import.meta.url))]],
  ['/viewer.css', ['text/css', readFileSync(new URL('public/viewer.css', import.meta.url))]]
]);

async function json(req) {
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 65536) throw fail(413, 'Request too large');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString()); }
  catch { throw fail(400, 'Invalid JSON'); }
}
function sdp(value) {
  if (typeof value !== 'string' || !value.startsWith('v=0') || value.length > 60000)
    throw fail(400, 'Invalid session description');
  return value;
}

export function createServer({ publishKey, now = Date.now, ttl = 3600000,
  idle = 45000, maxSessions = 20, turnUrls = [], turnSecret = '', stunUrl = '' } = {}) {
  if (!publishKey || publishKey.length < 32) throw Error('PUBLISH_KEY must be at least 32 characters');
  if (turnUrls.length && !turnSecret) throw Error('TURN_SHARED_SECRET is required with TURN_URLS');
  const sessions = new Map();
  function prune() {
    for (const [id, s] of sessions) {
      if (s.expiresAt <= now() || s.lastHost + idle <= now()) sessions.delete(id);
    }
  }
  function iceServers(expiresAt) {
    const result = stunUrl ? [{ urls: [stunUrl] }] : [];
    if (turnUrls.length) {
      const username = `${Math.ceil(expiresAt / 1000)}:${token()}`;
      result.push({ urls: turnUrls, username,
        credential: createHmac('sha1', turnSecret).update(username).digest('base64') });
    }
    return result;
  }
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    const send = (status, data) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(data));
    };
    try {
      prune();
      const path = new URL(req.url, 'http://localhost').pathname;
      if (req.method === 'GET' && assets.has(path)) {
        const [type, body] = assets.get(path);
        res.writeHead(200, { 'Content-Type': type }); res.end(body); return;
      }
      const auth = req.headers.authorization?.replace(/^Bearer /, '');
      if (req.method === 'POST' && path === '/api/sessions') {
        if (!equal(auth, publishKey)) throw fail(401, 'Publisher authorization required');
        if (sessions.size >= maxSessions) throw fail(429, 'Session limit reached');
        const id = token(), publisherToken = token(), viewerToken = token();
        const expiresAt = now() + ttl;
        const s = { publisherToken, viewerToken, expiresAt, lastHost: now(),
          active: false, offer: null, answer: null, viewerAccess: null, iceServers: iceServers(expiresAt) };
        sessions.set(id, s);
        send(201, { id, publisherToken, viewerToken, expiresAt, iceServers: s.iceServers }); return;
      }
      const match = /^\/api\/sessions\/([\w-]+)(?:\/(join|host|answer))?$/.exec(path);
      if (!match) throw fail(404, 'Not found');
      const [, id, action] = match;
      const s = sessions.get(id);
      if (!s) throw fail(404, 'Sharing ended or link expired');
      const publisher = equal(auth, s.publisherToken);
      if (action === 'join' && req.method === 'POST') {
        if (!equal(auth, s.viewerToken)) throw fail(401, 'Invalid viewing link');
        if (s.viewerAccess) throw fail(409, 'This link has already been used. Ask for a new link.');
        s.viewerAccess = token();
        send(200, { accessToken: s.viewerAccess }); return;
      }
      const viewer = equal(auth, s.viewerAccess);
      if (!publisher && !viewer) throw fail(401, 'Invalid session authorization');
      if (!action && req.method === 'GET') {
        send(200, { offer: s.offer, answer: publisher ? s.answer : undefined,
          active: s.active, expiresAt: s.expiresAt, iceServers: s.iceServers,
          viewerJoined: !!s.viewerAccess }); return;
      }
      if (action === 'host' && req.method === 'PUT' && publisher) {
        const body = await json(req);
        if (typeof body.active !== 'boolean') throw fail(400, 'Missing active state');
        if (body.offer !== undefined) {
          if (s.offer) throw fail(409, 'Offer already set');
          s.offer = sdp(body.offer);
        }
        s.active = body.active; s.lastHost = now(); send(200, {}); return;
      }
      if (action === 'answer' && req.method === 'PUT' && viewer) {
        if (!s.offer || s.answer) throw fail(409, 'Answer not expected');
        s.answer = sdp((await json(req)).answer); send(200, {}); return;
      }
      if (!action && req.method === 'DELETE' && publisher) {
        sessions.delete(id); send(200, {}); return;
      }
      throw fail(403, 'Operation not permitted');
    } catch (error) { send(error.status || 500, { error: error.status ? error.message : 'Server error' }); }
  });
  const timer = setInterval(prune, 10000); timer.unref();
  server.on('close', () => clearInterval(timer));
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  createServer({ publishKey: process.env.PUBLISH_KEY,
    turnUrls: (process.env.TURN_URLS || '').split(',').filter(Boolean),
    turnSecret: process.env.TURN_SHARED_SECRET || '', stunUrl: process.env.STUN_URL || ''
  }).listen(Number(process.env.PORT || 8080), process.env.HOST || '127.0.0.1', () => {
    console.log('TrashUSBcam signaling server listening');
  });
}
