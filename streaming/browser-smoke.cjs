#!/usr/bin/env node
// Real browser-to-browser media test of the viewer, using a synthetic publisher.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');

(async () => {
  const { createServer } = await import('./server.mjs');
  const key = 'browser-smoke-publisher-key-32-characters';
  const server = createServer({ publishKey: key });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let browser, heartbeat;
  try {
    browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
    const session = await (await fetch(base + '/api/sessions', { method: 'POST', headers: { Authorization: `Bearer ${key}` } })).json();
    const path = `${base}/api/sessions/${session.id}`;
    async function host(method, suffix = '', body) {
      const response = await fetch(path + suffix, { method, headers: {
        Authorization: `Bearer ${session.publisherToken}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body) });
      assert.equal(response.status, 200);
      return response.json();
    }
    const publisher = await browser.newPage();
    await publisher.goto(base);
    const offer = await publisher.evaluate(async () => {
      const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 240;
      document.body.append(canvas);
      const context = canvas.getContext('2d');
      window.paint = setInterval(() => {
        context.fillStyle = 'red'; context.fillRect(0, 0, 160, 240);
        context.fillStyle = 'blue'; context.fillRect(160, 0, 160, 240);
      }, 100);
      window.publisher = new RTCPeerConnection();
      window.publisher.addTrack(canvas.captureStream(10).getVideoTracks()[0]);
      await window.publisher.setLocalDescription(await window.publisher.createOffer());
      await new Promise(resolve => {
        if (window.publisher.iceGatheringState === 'complete') return resolve();
        window.publisher.onicegatheringstatechange = () => {
          if (window.publisher.iceGatheringState === 'complete') resolve();
        };
      });
      return window.publisher.localDescription.sdp;
    });
    let active = true;
    await host('PUT', '/host', { active, offer });
    heartbeat = setInterval(() => host('PUT', '/host', { active }).catch(() => {}), 1000);
    const url = `${base}/#${session.id}/${session.viewerToken}`;
    const viewer = await browser.newPage();
    const errors = []; viewer.on('pageerror', error => errors.push(error.message));
    await viewer.goto(url);
    assert.equal(new URL(viewer.url()).hash, '');
    await viewer.getByRole('button', { name: 'Watch live' }).click();
    let answer;
    for (let n = 0; n < 100; n++) {
      answer = (await host('GET')).answer;
      if (answer) break;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    assert.ok(answer, 'viewer produced an SDP answer');
    await publisher.evaluate(answer => window.publisher.setRemoteDescription({ type: 'answer', sdp: answer }), answer);
    await viewer.waitForFunction(() => document.querySelector('#status').textContent === 'Live');
    await viewer.waitForFunction(() => document.querySelector('video').getVideoPlaybackQuality().totalVideoFrames > 3);
    const pixels = await viewer.evaluate(() => {
      const video = document.querySelector('video');
      const canvas = document.createElement('canvas'); canvas.width = 320; canvas.height = 240;
      const context = canvas.getContext('2d'); context.drawImage(video, 0, 0);
      return [...context.getImageData(30, 30, 1, 1).data, ...context.getImageData(200, 30, 1, 1).data];
    });
    assert.ok(pixels[0] > 200 && pixels[2] < 40, 'left side decoded red');
    assert.ok(pixels[4] < 40 && pixels[6] > 200, 'right side decoded blue');
    const second = await browser.newPage(); await second.goto(url);
    await second.getByRole('button', { name: 'Watch live' }).click();
    await second.waitForFunction(() => document.querySelector('#status').textContent.includes('already been used'));
    active = false; await host('PUT', '/host', { active });
    await viewer.waitForFunction(() => document.querySelector('video').hidden && document.querySelector('#status').textContent.startsWith('Paused'));
    active = true; await host('PUT', '/host', { active });
    await viewer.waitForFunction(() => !document.querySelector('video').hidden);
    clearInterval(heartbeat); await host('DELETE');
    await viewer.waitForFunction(() => document.querySelector('video').hidden && document.querySelector('#status').textContent.includes('ended'));
    assert.deepEqual(errors, []);
    console.log('Browser media, decoded colors, single viewer, pause/resume, and revocation passed.');
  } finally {
    clearInterval(heartbeat); await browser?.close();
    await new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
