// The ISS window's pure part (IssDirectJs.PUR), run under node (IssDirectJsTest).
// By hand: node iss-direct-test.js iss.js (iss.js: IssDirectJs.PUR + module.exports).
const assert = require('assert');
const { issSource, issMer, issKm, issAutos, ISS_CHAINE, ISS_DERNIER } = require(require('path').resolve(process.argv[2]));

const EMBED = 'https://www.youtube-nocookie.com/embed/awQzjn72bI0?autoplay=1&mute=1&playsinline=1&rel=0';
// NASA's stream, however it is pasted: muted, autoplaying, inline, from the no-cookie player.
['awQzjn72bI0', ' awQzjn72bI0 ', 'https://www.youtube.com/watch?v=awQzjn72bI0', 'www.youtube.com/watch?v=awQzjn72bI0&t=42s', 'https://m.youtube.com/watch?feature=share&v=awQzjn72bI0',
  'https://youtu.be/awQzjn72bI0?si=abc', 'https://www.youtube.com/live/awQzjn72bI0?feature=shared', 'https://www.youtube.com/embed/awQzjn72bI0',
  'https://www.youtube-nocookie.com/embed/awQzjn72bI0?autoplay=1', 'http://youtube.com/shorts/awQzjn72bI0'].forEach(s => {
  const r = issSource(s);
  assert(r, s);
  assert.strictEqual(r.type, 'youtube', s); assert.strictEqual(r.id, 'awQzjn72bI0', s); assert.strictEqual(r.url, EMBED, s);
  assert.strictEqual(r.page, 'https://www.youtube.com/watch?v=awQzjn72bI0');
});
// A channel's live, whatever the video of the day.
{
  const c = 'UCLA_DiR1FfKNvjuUpBHmylQ';
  for (const s of ['https://www.youtube.com/channel/' + c + '/live', 'https://www.youtube.com/embed/live_stream?channel=' + c]) {
    const r = issSource(s);
    assert.strictEqual(r.channel, c, s);
    assert.strictEqual(r.url, 'https://www.youtube-nocookie.com/embed/live_stream?channel=' + c + '&autoplay=1&mute=1&playsinline=1&rel=0');
  }
}
// A direct video; HLS said so (Safari only).
assert.deepStrictEqual(issSource('https://example.org/iss/live.mp4?x=1'), { type: 'video', url: 'https://example.org/iss/live.mp4?x=1', hls: false, page: 'https://example.org/iss/live.mp4?x=1' });
assert.strictEqual(issSource('https://example.org/hls/index.m3u8').hls, true);
// Not understood: nothing loaded.
['', null, undefined, 'bonjour', 'awQzjn72bI', 'javascript:alert(1)', 'https://www.youtube.com/@NASA/live', 'https://example.org/page.html',
  'https://www.youtube.com/watch?v=<script>', 'https://evil.example/embed/awQzjn72bI0', 'ftp://example.org/a.mp4', 'https://www.youtube.com/channel/UCbad/live'].forEach(s =>
  assert.strictEqual(issSource(s), null, String(s)));

// The automatic NASA source: today's live when found, then the channel's live, then the last ID known; each one understood.
{
  const ch = 'https://www.youtube.com/embed/live_stream?channel=' + ISS_CHAINE;
  assert.deepStrictEqual(issAutos('abcDEF_12-x'), ['abcDEF_12-x', ch, ISS_DERNIER]);
  assert.deepStrictEqual(issAutos(null), [ch, ISS_DERNIER]);
  assert.deepStrictEqual(issAutos(ISS_DERNIER), [ISS_DERNIER, ch]);
  assert.deepStrictEqual(issAutos('<x>'), [ch, ISS_DERNIER]);
  issAutos('abcDEF_12-x').forEach(s => assert(issSource(s), s));
  assert.strictEqual(issSource(ch).channel, ISS_CHAINE);
}
// The seas under the ISS's band.
const mers = [[45, -30, 'l’océan Atlantique'], [-20, -20, 'l’océan Atlantique'], [-20, 80, 'l’océan Indien'], [0, -140, 'l’océan Pacifique'], [30, 160, 'l’océan Pacifique'],
  [-40, -100, 'l’océan Pacifique'], [35, 18, 'la Méditerranée'], [43, 34, 'la mer Noire'], [20, 38, 'la mer Rouge'], [25, -90, 'le golfe du Mexique'],
  [15, -75, 'la mer des Caraïbes'], [12, 115, 'la mer de Chine méridionale'], [45, -4, 'l’océan Atlantique'], [56, 3, 'la mer du Nord'], [-35, 120, 'l’océan Indien'],
  [10, -100, 'l’océan Pacifique'], [40, 200, 'l’océan Pacifique'], [-65, 0, 'l’océan Austral']];
mers.forEach(m => assert.strictEqual(issMer(m[0], m[1]), m[2], m.join(' ')));

// Paris → Madrid ≈ 1 053 km; a point to itself, 0.
assert(Math.abs(issKm(48.857, 2.352, 40.417, -3.704) - 1053) < 10);
assert.strictEqual(issKm(10, 20, 10, 20), 0);
console.log('ok');
