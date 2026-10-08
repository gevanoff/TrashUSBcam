const status = document.querySelector('#status');
const video = document.querySelector('#video');
const join = document.querySelector('#join');
const parts = location.hash.slice(1).split('/');
// Keep the capability out of navigation history, server logs, and message previews.
history.replaceState(null, '', location.pathname);
let pc, timer, stopped = false;
function end(message) {
  stopped = true; clearTimeout(timer); pc?.close(); video.srcObject = null;
  video.hidden = true; status.textContent = message;
}
function gathered(connection) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { cleanup(); reject(Error('Connection setup timed out. Ask for a new link.')); }, 20000);
    const check = () => { if (connection.iceGatheringState === 'complete') { cleanup(); resolve(); } };
    const cleanup = () => { clearTimeout(timeout); connection.removeEventListener('icegatheringstatechange', check); };
    connection.addEventListener('icegatheringstatechange', check); check();
  });
}
if (parts.length === 2 && parts.every(p => /^[\w-]{43}$/.test(p))) {
  join.hidden = false; status.textContent = 'Ready to join this private camera stream.';
  join.onclick = async () => {
    join.hidden = true;
    const [id, capability] = parts;
    let auth = capability, negotiated = false;
    async function api(suffix = '', method = 'GET', body) {
      const response = await fetch(`/api/sessions/${id}${suffix}`, {
        method, headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
      });
      const data = await response.json();
      if (!response.ok) throw Error(data.error || 'Connection failed');
      return data;
    }
    try {
      auth = (await api('/join', 'POST')).accessToken;
      status.textContent = 'Waiting for the sender…';
      const poll = async () => {
        if (stopped) return;
        try {
          const state = await api();
          if (stopped) return;
          if (state.offer && !negotiated) {
            negotiated = true;
            pc = new RTCPeerConnection({ iceServers: state.iceServers });
            pc.ontrack = event => { video.srcObject = new MediaStream([event.track]); video.play().catch(() => {}); };
            pc.onconnectionstatechange = () => {
              if (pc.connectionState === 'failed' || pc.connectionState === 'closed') end('Stream disconnected. Ask the sender for a new link.');
            };
            await pc.setRemoteDescription({ type: 'offer', sdp: state.offer });
            await pc.setLocalDescription(await pc.createAnswer());
            await gathered(pc);
            if (stopped) return;
            await api('/answer', 'PUT', { answer: pc.localDescription.sdp });
          }
          video.hidden = !state.active || pc?.connectionState !== 'connected';
          status.textContent = !state.active ? 'Paused — waiting for the sender’s camera.' :
            pc?.connectionState === 'connected' ? 'Live' : 'Connecting…';
          timer = setTimeout(poll, 1000);
        } catch (error) { end(error.message); }
      };
      await poll();
    } catch (error) { end(error.message); }
  };
}
window.addEventListener('pagehide', () => end('Viewing ended. Ask for a new link to reconnect.'));
