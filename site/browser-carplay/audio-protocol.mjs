// Audio-only WebRTC signaling over the already approved local session.
export const AUDIO_TRANSPORT = 'webrtc-opus';
export const MAX_AUDIO_SDP = 6000;
export const MAX_AUDIO_CANDIDATES = 32;
export const MAX_AUDIO_CANDIDATE = 1024;
export const positiveId = value => Number.isSafeInteger(value) && value > 0;

export function validAudioSdp(sdp, direction) {
  if (typeof sdp !== 'string' || sdp.length > MAX_AUDIO_SDP || !sdp.startsWith('v=0\r\n') || /[^\x09\x0a\x0d\x20-\x7e]/.test(sdp)) return false;
  const lines = sdp.split(/\r?\n/);
  if (lines.some(line => line.length > MAX_AUDIO_CANDIDATE)) return false;
  const media = lines.filter(line => line.startsWith('m='));
  if (media.length !== 1 || !/^m=audio [1-9]\d* UDP\/TLS\/RTP\/SAVPF \d+(?: \d+)*$/.test(media[0])) return false;
  const directions = lines.filter(line => /^a=(?:sendrecv|sendonly|recvonly|inactive)$/.test(line));
  if (directions.length !== 1 || directions[0] !== `a=${direction}` || !lines.includes('a=rtcp-mux')) return false;
  if (!lines.some(line => /^a=fingerprint:sha-256 (?:[0-9a-f]{2}:){31}[0-9a-f]{2}$/i.test(line))) return false;
  const opus = lines.find(line => /^a=rtpmap:\d+ opus\/48000\/2$/i.test(line));
  if (!opus || !media[0].split(' ').slice(3).includes(opus.split(/[ :]/)[1])) return false;
  if (lines.some(line => line.startsWith('a=crypto:'))) return false;
  const candidates = lines.filter(line => line.startsWith('a=candidate:'));
  return candidates.length <= MAX_AUDIO_CANDIDATES && candidates.every(line => validAudioCandidate({ candidate: line.slice(2), sdpMid: '0', sdpMLineIndex: 0 }));
}

export function validAudioCandidate(message) {
  if (typeof message.candidate !== 'string' || message.candidate.length > MAX_AUDIO_CANDIDATE ||
      /[\r\n\0]/.test(message.candidate) || typeof message.sdpMid !== 'string' ||
      !/^[A-Za-z0-9_-]{1,32}$/.test(message.sdpMid) || message.sdpMLineIndex !== 0) return false;
  // Keep bounded ICE syntax without constraining the selected network topology.
  if (!/^candidate:[A-Za-z0-9+/]{1,64} 1 (?:udp|tcp) \d{1,10} [A-Za-z0-9.:-]{1,253} \d{1,5} typ (?:host|srflx|prflx|relay)(?: [A-Za-z0-9.:%_+/-]+ [A-Za-z0-9.:%_+/-]+)*$/i.test(message.candidate)) return false;
  const fields = message.candidate.split(' '), port = Number(fields[5]), priority = Number(fields[3]);
  return port > 0 && port <= 65535 && priority <= 0xffffffff && validIceAddress(fields[4]);
}

export function validIceAddress(address) {
  if (/\.local$/i.test(address)) return address.length <= 253 && address.split('.').every(label =>
    label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label));
  if (address.includes(':')) {
    if (!/^[0-9a-f:.]+$/i.test(address)) return false;
    try { new URL(`http://[${address}]/`); return true; } catch { return false; }
  }
  const fields = address.split('.'), octets = fields.map(Number);
  return fields.length === 4 && octets.every((value, index) =>
    Number.isInteger(value) && value >= 0 && value <= 255 && String(value) === fields[index]);
}

// RFC 7587 stereo is a receiver preference. WebRTC answers commonly omit it,
// so preserve all existing parameters and explicitly request stereo decoding.
export function preferOpusStereo(sdp) {
  const lines = sdp.split('\r\n');
  const payload = lines.find(line => /^a=rtpmap:\d+ opus\/48000\/2$/i.test(line))?.split(/[ :]/)[1];
  if (!payload) return sdp;
  const prefix = `a=fmtp:${payload} `;
  const index = lines.findIndex(line => line.startsWith(prefix));
  if (index < 0) lines.splice(lines.findIndex(line => line.startsWith(`a=rtpmap:${payload} `)) + 1, 0, `${prefix}stereo=1`);
  else {
    const parameters = lines[index].slice(prefix.length).split(';').map(value => value.trim()).filter(value => value && !/^stereo\s*=/i.test(value));
    lines[index] = `${prefix}${[...parameters, 'stereo=1'].join(';')}`;
  }
  return lines.join('\r\n');
}
