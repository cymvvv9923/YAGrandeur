// 다온 서버 (의존성 없음: node server.js)
// - 서클(가족·친구 모임)·프로필·메시지·「들었어요」·일기 저장과 전달
// - 기기 열쇠: 폰·차 모두 모임을 만들거나 초대 코드로 들어갈 때 열쇠를 받고, 열쇠로만 자기 모임에 접근한다 (로그인 없음)
// - 키워드 필터, 신고
// - 폰 웹(../web) 정적 제공
// 차의 현재 위치는 받지 않는다. 차는 메시지 장소 목록을 내려받아 차 안에서 근처 여부를 계산한다.
// 로그인은 없다. 초대 코드를 아는 기기면 그 모임으로 본다.
// 차는 한 사람의 차다(ownerId). 가족·친구가 각자 자기 차에서 서로에게 남기고 듣는다.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8090);
// 배포 때는 PROD=1로 켠다: 시연용 API(/api/dev/*)를 막는다
const PROD = process.env.PROD === '1';
const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
// 시연 데이터: seed.json(로드레터 시나리오 가족) · seed-hyeonggyu.json(형규의 퇴근길) · seed-empty.json(아무도 없음)
// SEED=empty 로 켜면 처음 설치하는 상태로 시작한다. /api/dev/reset?seed=이름 으로 바꿀 수도 있다.
const seedFile = (name) => path.join(__dirname, name && /^[a-z0-9-]+$/.test(name) ? `seed-${name}.json` : 'seed.json');
const SEED_FILE = seedFile(process.env.SEED);
const MEDIA_DIR = path.join(DATA_DIR, 'media');
const WEB_DIR = path.join(__dirname, '..', 'web');

// 부적절 내용 필터 (MVP: 키워드). 필요하면 여기에 추가.
const BLOCKED_WORDS = ['시발', '씨발', '병신', '개새끼', '죽어', '꺼져', '미친놈', '미친년'];
// 신고가 이 수 이상이면 숨김
const REPORT_HIDE_THRESHOLD = 1;
// 프로필 색 (차·폰이 같은 이름을 쓴다)
const COLORS = ['blue', 'green', 'orange', 'purple', 'pink', 'teal', 'red', 'gray'];
const MAX_PHOTO_BYTES = 1.5 * 1024 * 1024;

fs.mkdirSync(MEDIA_DIR, { recursive: true });

/** 시연용 목소리 파일(seed-media)을 media 폴더에 둔다. 이미 있으면 그대로 */
function copySeedMedia() {
  const dir = path.join(__dirname, 'seed-media');
  if (!fs.existsSync(dir)) return;
  for (const f of fs.readdirSync(dir)) {
    const to = path.join(MEDIA_DIR, f);
    if (!fs.existsSync(to)) fs.copyFileSync(path.join(dir, f), to);
  }
}

function loadDb() {
  if (!fs.existsSync(DB_FILE)) fs.copyFileSync(SEED_FILE, DB_FILE);
  copySeedMedia();
  const d = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  // 이전 형식(db.json)에서 올라온 경우 빈 칸을 채운다
  d.devices = d.devices || [];
  d.circles.forEach((c) => {
    c.inviteCode = c.inviteCode || newInviteCode(d);
    c.members.forEach((m, i) => { m.color = m.color || COLORS[i % COLORS.length]; m.title = m.title || ''; });
    c.members.forEach((m) => { m.code = m.code || newInviteCode(d); }); // 사람마다 내 코드
  });
  return d;
}
let db = loadDb();
function saveDb() {
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
}

function newId(prefix) {
  return prefix + '_' + crypto.randomBytes(4).toString('hex');
}
function randomToken(bytes = 24) {
  return crypto.randomBytes(bytes).toString('base64url');
}
/**
 * 초대 코드: 차 화면에서도 칠 수 있게 짧게 (헷갈리는 0·O·1·I 빼고 6자리).
 * 서로 다른 모임이 같은 코드를 갖지 않게 한다.
 */
function newInviteCode(d = db) {
  const INVITE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // 함수 안에 둔다: 서버가 켜질 때 loadDb가 이 함수를 먼저 부른다
  for (;;) {
    let code = '';
    for (let i = 0; i < 6; i++) code += INVITE_CHARS[crypto.randomInt(0, INVITE_CHARS.length)];
    if (!d.circles.some((c) => c.inviteCode === code || (c.members || []).some((m) => m.code === code))) return code;
  }
}
/** 넣은 초대 코드로 모임 찾기 (대소문자·띄어쓰기·하이픈 무시) */
function circleByInvite(code) {
  const norm = (x) => String(x || '').toUpperCase().replace(/[\s-]/g, '');
  const want = norm(code);
  return want ? db.circles.find((c) => c.inviteCode && norm(c.inviteCode) === want) : null;
}
/** 사람마다 받은 「내 코드」로 그 사람 찾기: { circle, member } */
function personByInvite(code) {
  const norm = (x) => String(x || '').toUpperCase().replace(/[\s-]/g, '');
  const want = norm(code);
  if (!want) return null;
  for (const circle of db.circles) {
    const member = circle.members.find((m) => m.code && norm(m.code) === want);
    if (member) return { circle, member };
  }
  return null;
}
function hash(s) {
  return crypto.createHash('sha256').update(String(s)).digest('hex');
}

function containsBlocked(text) {
  const t = (text || '').replace(/\s/g, '');
  return BLOCKED_WORDS.find((w) => t.includes(w));
}

function memberName(circle, memberId) {
  const m = circle.members.find((x) => x.id === memberId);
  return m ? m.name : memberId;
}

function send(res, status, body, type = 'application/json; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Access-Control-Allow-Origin': '*' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.webm': 'audio/webm',
  '.wav': 'audio/wav',
  '.ogg': 'audio/ogg',
  '.m4a': 'audio/mp4',
  '.mp3': 'audio/mpeg',
  '.jpg': 'image/jpeg',
  '.png': 'image/png',
  '.webp': 'image/webp',
};

function serveFile(res, file) {
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(res, 404, { error: 'not found' });
  send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream');
}

/** /media/파일 을 지운다 (메시지·프로필 사진을 지울 때) */
function removeMedia(url) {
  if (!url || !url.startsWith('/media/')) return;
  fs.rm(path.join(MEDIA_DIR, path.basename(url)), { force: true }, () => {});
}

function publicMessage(m) {
  const { reports, ...rest } = m;
  return { when: 'place', ...rest };
}

/** 서클을 밖으로 보낼 때는 초대 코드를 뺀다 (초대 코드는 폰의 「가족 초대」에서만) */
function publicCircle(c) {
  const { inviteCode, ...rest } = c;
  return { ...rest, members: c.members.map(({ code, ...m }) => m) };
}

// 메시지가 열리는 조건. 장소 말고는 차 신호로 판단한다 (판단은 차 안에서).
const WHEN_LABELS = {
  place: '장소',
  rain: '비 오는 날',
  night: '밤길',
  together: '옆자리에 누가 탄 날',
  door: '차에 탈 때',
  start: '시동 걸 때',
  park: '도착해 차를 세울 때',
  long: '오래 운전할 때',
  warning: '경고등이 켜질 때',
  region: '그 지역에 들어가면',
};
// 장소(좌표)를 함께 받는 조건: place 그 근처 · park 그곳에 세울 때(없으면 어디서든) · region 그 시·군·구
const WITH_PLACE = ['place', 'park', 'region'];
const REPEATS = ['once', 'always', 'weekly'];

/** 받는 사람·시간·횟수·답장 조건을 정리한다. 판단은 받는 차 안에서 한다. */
function cleanConditions(b, circle) {
  const c = {};
  if (Array.isArray(b.to)) c.to = b.to.filter((id) => circle.members.some((m) => m.id === id)).slice(0, 20);
  if (b.replyTo && db.messages.some((m) => m.id === b.replyTo && m.circleId === circle.id)) c.replyTo = b.replyTo;
  const h = (v) => (Number.isInteger(v) && v >= 0 && v <= 23 ? v : undefined);
  if (h(b.hourFrom) !== undefined && h(b.hourTo) !== undefined) { c.hourFrom = b.hourFrom; c.hourTo = b.hourTo; }
  if (Array.isArray(b.days)) c.days = [...new Set(b.days.filter((d) => Number.isInteger(d) && d >= 1 && d <= 7))];
  if (typeof b.notBefore === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(b.notBefore)) c.notBefore = b.notBefore;
  if (typeof b.notAfter === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(b.notAfter)) c.notAfter = b.notAfter;
  c.repeat = REPEATS.includes(b.repeat) ? b.repeat : 'once';
  if (Number.isInteger(b.longMin) && b.longMin >= 30 && b.longMin <= 600) c.longMin = b.longMin;
  if (typeof b.regionCode === 'string' && /^\d{2,5}$/.test(b.regionCode)) c.regionCode = b.regionCode;
  return c;
}

// 기기 열쇠 -----------------------------------------------------------------

/** 새 기기(폰·차)를 만들고 열쇠를 한 번만 돌려준다. 서버에는 열쇠의 해시만 남긴다. */
function addDevice(circleId, kind, name, vinHash, ownerId) {
  const key = randomToken();
  const device = { id: newId(kind), circleId, kind, name, ownerId: ownerId || null, vinHash: vinHash || null, keyHash: hash(key), createdAt: new Date().toISOString(), lastSeenAt: null };
  db.devices.push(device);
  return { device, key };
}

/** 요청의 열쇠(X-Key)로 기기를 찾는다 */
function deviceOf(req) {
  const key = req.headers['x-key'];
  if (!key) return null;
  const h = hash(key);
  const d = db.devices.find((x) => x.keyHash === h);
  if (d) d.lastSeenAt = new Date().toISOString();
  return d || null;
}

/**
 * 열쇠가 있는 요청만 통과시킨다. 주소의 circleId는 믿지 않고 열쇠의 서클과 같아야 한다.
 * kind를 주면 그 기기 종류만 허용한다 ('phone' | 'car').
 */
function guard(handler, { kind, circleParam = true } = {}) {
  return async (req, res, p) => {
    const device = deviceOf(req);
    if (!device) return send(res, 401, { error: '이 기기는 모임에 연결되어 있지 않아요' });
    const circle = db.circles.find((c) => c.id === device.circleId);
    if (!circle) return send(res, 401, { error: '모임이 없어졌어요' });
    if (circleParam && p.circleId && p.circleId !== circle.id && p.circleId !== 'me') return send(res, 403, { error: '다른 모임의 정보예요' });
    if (kind && device.kind !== kind) return send(res, 403, { error: kind === 'phone' ? '폰에서만 할 수 있어요' : '차에서만 할 수 있어요' });
    return handler(req, res, p, { device, circle });
  };
}

/** 메시지 주소(/api/messages/:id)는 그 메시지가 내 서클 것인지 본다 */
function myMessage(res, ctx, messageId) {
  const msg = db.messages.find((m) => m.id === messageId && m.circleId === ctx.circle.id);
  if (!msg) send(res, 404, { error: 'message not found' });
  return msg;
}

// 라우터 ------------------------------------------------------------------

const routes = [];
function route(method, pattern, handler) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '$');
  routes.push({ method, re, keys, handler });
}

function cleanName(name) {
  return String(name || '').trim().slice(0, 12);
}
function cleanColor(color, i = 0) {
  return COLORS.includes(color) ? color : COLORS[i % COLORS.length];
}

// 모임 만들기·들어가기 (폰·차, 열쇠 없이) ------------------------------------------

const CIRCLE_TYPES = ['family', 'couple', 'friends'];

/**
 * 이 요청을 보낸 기기를 만든다. 차면 고유번호 해시(vinHash)를 받아,
 * 같은 차가 이미 어딘가에 있으면 그 연결은 끊는다 (차를 팔았거나 앱을 다시 깐 경우).
 */
function addRequester(b, circle, ownerId) {
  if (b.device !== 'car') return addDevice(circle.id, 'phone', '폰');
  const vinHash = b.vinHash ? String(b.vinHash).slice(0, 64) : null;
  // 같은 차가 같은 모임에 다시 들어오면 예전 연결을 지운다. 다른 모임에는 따로 들어갈 수 있다 (한 차가 가족·친구 모임 모두에).
  if (vinHash) db.devices = db.devices.filter((d) => !(d.kind === 'car' && d.vinHash === vinHash && d.circleId === circle.id));
  const carName = cleanName(b.carName) || (ownerId ? `${memberName(circle, ownerId)}의 차` : '우리 차');
  return addDevice(circle.id, 'car', carName, vinHash, ownerId);
}

// 새 모임 만들기: 모임 + 첫 프로필 + 이 기기의 열쇠. type은 family 가족 · friends 친구 · couple 연인
// 차에서 만들면 만든 사람이 그 차의 주인이 된다.
route('POST', '/api/circles', async (req, res) => {
  const b = await readBody(req);
  const type = CIRCLE_TYPES.includes(b.type) ? b.type : 'family';
  const circleName = cleanName(b.circleName) || (type === 'family' ? '우리집' : '우리 모임');
  const name = cleanName(b.memberName);
  if (!name) return send(res, 400, { error: '내 이름을 써 주세요' });
  if (containsBlocked(name) || containsBlocked(circleName)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
  if (b.agreed !== true) return send(res, 400, { error: '개인정보 처리에 동의해야 만들 수 있어요' });
  const member = { id: newId('m'), name, title: cleanName(b.title), color: cleanColor(b.color), code: newInviteCode() };
  const circle = { id: newId('c'), name: circleName, type, members: [member], inviteCode: newInviteCode(), createdAt: new Date().toISOString() };
  db.circles.push(circle);
  const { device, key } = addRequester(b, circle, member.id);
  saveDb();
  send(res, 201, { key, circle: publicCircle(circle), memberId: member.id, device: publicDevice(device) });
});

// 초대 코드로 들어가기: 이 기기의 열쇠를 받는다.
// 사람마다 받은 「내 코드」로 들어오면 그 사람 차가 된다 (차 화면에서 주인을 고르지 않는다).
// 모임 코드는 폰만 (차는 누구 차인지 알 수 없어서 내 코드로만).
route('POST', '/api/join', async (req, res) => {
  const b = await readBody(req);
  const person = personByInvite(b.inviteCode);
  const circle = person ? person.circle : circleByInvite(b.inviteCode);
  if (!circle) return send(res, 404, { error: '초대 코드가 맞지 않거나 바뀌었어요. 모임 사람에게 새 코드를 받아 주세요' });
  if (b.device === 'car' && !person) return send(res, 400, { error: '차는 사람마다 받은 「내 코드」로 들어와요. 모임 사람에게 내 코드를 물어봐 주세요' });
  if (b.agreed !== true) return send(res, 400, { error: '개인정보 처리에 동의해야 들어갈 수 있어요' });
  const { device, key } = addRequester(b, circle, person && b.device === 'car' ? person.member.id : null);
  saveDb();
  send(res, 201, { key, circle: publicCircle(circle), device: publicDevice(device) });
});

// 기기 관리 --------------------------------------------------------------

function publicDevice(d) {
  return { id: d.id, kind: d.kind, name: d.name, ownerId: d.ownerId || null, createdAt: d.createdAt, lastSeenAt: d.lastSeenAt };
}

// 이 기기와 내 가족
route('GET', '/api/me', guard((req, res, p, { device, circle }) => {
  send(res, 200, { device: publicDevice(device), circle: publicCircle(circle) });
}, { circleParam: false }));

// 모임의 차 목록 (폰 웹의 「차 관리」, 차의 「모임 관리」). me는 묻는 기기 자신인지
route('GET', '/api/circles/:circleId/cars', guard((req, res, p, { device, circle }) => {
  send(res, 200, db.devices.filter((d) => d.circleId === circle.id && d.kind === 'car').map((d) => ({ ...publicDevice(d), me: d.id === device.id })));
}));

// 차 이름 바꾸기 (폰이나 차 화면 어디서나 모임의 차)
route('POST', '/api/devices/:deviceId/rename', guard(async (req, res, p, { device, circle }) => {
  const target = db.devices.find((d) => d.id === (p.deviceId === 'me' ? device.id : p.deviceId) && d.circleId === circle.id);
  if (!target) return send(res, 404, { error: 'device not found' });
  const name = cleanName((await readBody(req)).name);
  if (!name) return send(res, 400, { error: '이름을 써 주세요' });
  target.name = name;
  saveDb();
  send(res, 200, publicDevice(target));
}, { circleParam: false }));

// 차 주인 바꾸기 (폰·차 어디서나 모임의 차). 'me'는 이 기기.
route('POST', '/api/devices/:deviceId/owner', guard(async (req, res, p, { device, circle }) => {
  const target = db.devices.find((d) => d.id === (p.deviceId === 'me' ? device.id : p.deviceId) && d.circleId === circle.id && d.kind === 'car');
  if (!target) return send(res, 404, { error: 'car not found' });
  const ownerId = (await readBody(req)).ownerId;
  if (!circle.members.some((m) => m.id === ownerId)) return send(res, 400, { error: '모임 사람 중에서 골라 주세요' });
  target.ownerId = ownerId;
  if (target.name === '우리 차') target.name = `${memberName(circle, ownerId)}의 차`;
  saveDb();
  send(res, 200, publicDevice(target));
}, { circleParam: false }));

// 기기 빼기: 모임의 차는 폰·차 어디서나, 폰은 그 폰에서만 (「이 차 연결 끊기」·「이 폰 연결 끊기」). 'me'는 이 기기.
route('POST', '/api/devices/:deviceId/delete', guard((req, res, p, { device, circle }) => {
  const id = p.deviceId === 'me' ? device.id : p.deviceId;
  const target = db.devices.find((d) => d.id === id && d.circleId === circle.id);
  if (!target) return send(res, 404, { error: 'device not found' });
  // 차 화면에서도 모임의 차는 관리할 수 있다. 폰은 폰 자신만 뺄 수 있게 차에서 막는다.
  if (device.kind === 'car' && target.kind !== 'car' && target.id !== device.id) return send(res, 403, { error: '폰 연결은 그 폰에서 끊어 주세요' });
  db.devices = db.devices.filter((d) => d.id !== target.id);
  saveDb();
  send(res, 200, { ok: true });
}, { circleParam: false }));

// 초대 코드 보기·바꾸기 (폰·차). 바꾸면 예전 코드로는 못 들어오고, 이미 들어온 기기는 그대로다.
// people: 사람마다 「내 코드」(그 사람 차가 이 코드로 들어오면 바로 그 사람 차가 된다)
const invitePeople = (circle) => {
  let added = false;
  circle.members.forEach((m) => { if (!m.code) { m.code = newInviteCode(); added = true; } });
  if (added) saveDb();
  return circle.members.map((m) => ({ id: m.id, name: m.name, code: m.code }));
};
route('GET', '/api/circles/:circleId/invite', guard((req, res, p, { circle }) => {
  send(res, 200, { inviteCode: circle.inviteCode, people: invitePeople(circle) });
}));
// memberId가 있으면 그 사람 코드만, 없으면 모임 코드(폰용)를 새로
route('POST', '/api/circles/:circleId/invite/reset', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req).catch(() => ({}));
  const m = b && b.memberId ? circle.members.find((x) => x.id === b.memberId) : null;
  if (m) m.code = newInviteCode(); else circle.inviteCode = newInviteCode();
  saveDb();
  send(res, 200, { inviteCode: circle.inviteCode, people: invitePeople(circle) });
}));

// 모임 통째로 지우기 (폰·차): 메시지·목소리·사진·일기·기록·기기 열쇠까지 전부
route('POST', '/api/circles/:circleId/delete', guard(async (req, res, p, { circle }) => {
  if ((await readBody(req)).confirm !== circle.name) return send(res, 400, { error: '확인을 위해 모임 이름을 그대로 써 주세요' });
  const msgs = db.messages.filter((m) => m.circleId === circle.id);
  msgs.forEach((m) => removeMedia(m.audioUrl));
  circle.members.forEach((m) => removeMedia(m.photoUrl));
  const ids = new Set(msgs.map((m) => m.id));
  db.messages = db.messages.filter((m) => m.circleId !== circle.id);
  db.heard = db.heard.filter((h) => !ids.has(h.messageId));
  db.diaries = db.diaries.filter((d) => d.circleId !== circle.id);
  db.devices = db.devices.filter((d) => d.circleId !== circle.id);
  db.circles = db.circles.filter((c) => c.id !== circle.id);
  saveDb();
  send(res, 200, { ok: true });
}));

// 서클 · 프로필 -----------------------------------------------------------

route('GET', '/api/circles/:circleId', guard((req, res, p, { circle }) => send(res, 200, publicCircle(circle))));

// 서클 이름·종류 바꾸기
route('POST', '/api/circles/:circleId/update', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req);
  if (b.name !== undefined) {
    const name = cleanName(b.name);
    if (!name) return send(res, 400, { error: '이름을 써 주세요' });
    circle.name = name;
  }
  if (b.type !== undefined) {
    if (!CIRCLE_TYPES.includes(b.type)) return send(res, 400, { error: 'type은 family, couple, friends' });
    circle.type = b.type;
  }
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 프로필 추가 (폰이 없는 가족도 이렇게 만든다)
route('POST', '/api/circles/:circleId/members', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req);
  const name = cleanName(b.name);
  if (!name) return send(res, 400, { error: '이름을 써 주세요' });
  if (containsBlocked(name)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
  if (circle.members.some((m) => m.name === name)) return send(res, 409, { error: '같은 이름이 이미 있어요' });
  circle.members.push({ id: newId('m'), name, title: cleanName(b.title), color: cleanColor(b.color, circle.members.length), code: newInviteCode() });
  saveDb();
  send(res, 201, publicCircle(circle));
}));

/** 이름이 바뀌면 이미 남긴 메시지·기록의 이름도 맞춘다 */
function renameEverywhere(member, name) {
  member.name = name;
  db.messages.filter((m) => m.authorId === member.id).forEach((m) => (m.authorName = name));
  db.heard.filter((h) => h.memberId === member.id).forEach((h) => (h.memberName = name));
  db.diaries.filter((d) => d.memberId === member.id).forEach((d) => (d.memberName = name));
}

// 프로필 고치기: 이름 · 호칭 · 색
route('POST', '/api/circles/:circleId/members/:memberId/update', guard(async (req, res, p, { circle }) => {
  const member = circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  const b = await readBody(req);
  if (b.name !== undefined) {
    const name = cleanName(b.name);
    if (!name) return send(res, 400, { error: '이름을 써 주세요' });
    if (containsBlocked(name)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
    if (circle.members.some((m) => m !== member && m.name === name)) return send(res, 409, { error: '같은 이름이 이미 있어요' });
    renameEverywhere(member, name);
  }
  if (b.title !== undefined) {
    if (containsBlocked(b.title)) return send(res, 422, { error: '쓸 수 없는 호칭이에요' });
    member.title = cleanName(b.title);
  }
  if (b.color !== undefined) member.color = cleanColor(b.color);
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 예전 차 앱이 쓰던 이름 바꾸기 주소
route('POST', '/api/circles/:circleId/members/:memberId/rename', guard(async (req, res, p, { circle }) => {
  const member = circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  const name = cleanName((await readBody(req)).name);
  if (!name) return send(res, 400, { error: '이름을 써 주세요' });
  if (containsBlocked(name)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
  renameEverywhere(member, name);
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 프로필 사진 올리기 (폰). jpg·png·webp, 1.5MB까지
route('POST', '/api/circles/:circleId/members/:memberId/photo', guard(async (req, res, p, { circle }) => {
  const member = circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  const b = await readBody(req);
  const ext = { jpg: 'jpg', jpeg: 'jpg', png: 'png', webp: 'webp' }[String(b.ext || '').toLowerCase()];
  if (!ext || !b.imageBase64) return send(res, 400, { error: 'jpg, png, webp 사진만 올릴 수 있어요' });
  const buf = Buffer.from(b.imageBase64, 'base64');
  if (buf.length > MAX_PHOTO_BYTES) return send(res, 413, { error: '사진이 너무 커요 (1.5MB까지)' });
  removeMedia(member.photoUrl);
  // 주소를 짐작할 수 없게 긴 무작위 이름을 쓴다
  const file = `p_${crypto.randomBytes(12).toString('hex')}.${ext}`;
  fs.writeFileSync(path.join(MEDIA_DIR, file), buf);
  member.photoUrl = '/media/' + file;
  saveDb();
  send(res, 200, publicCircle(circle));
}, { kind: 'phone' }));

route('POST', '/api/circles/:circleId/members/:memberId/photo/delete', guard((req, res, p, { circle }) => {
  const member = circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  removeMedia(member.photoUrl);
  delete member.photoUrl;
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 프로필 빼기: 그 사람이 남긴 메시지·목소리·사진·기록도 지운다
route('POST', '/api/circles/:circleId/members/:memberId/delete', guard((req, res, p, { circle }) => {
  const member = circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  if (circle.members.length <= 1) return send(res, 400, { error: '구성원이 최소 1명은 있어야 해요' });
  circle.members = circle.members.filter((m) => m.id !== p.memberId);
  removeMedia(member.photoUrl);
  const mine = db.messages.filter((m) => m.circleId === circle.id && m.authorId === p.memberId);
  mine.forEach((m) => removeMedia(m.audioUrl));
  const gone = new Set(mine.map((m) => m.id));
  db.messages = db.messages.filter((m) => !gone.has(m.id));
  db.heard = db.heard.filter((h) => h.memberId !== p.memberId && !gone.has(h.messageId));
  db.diaries = db.diaries.filter((d) => !(d.circleId === circle.id && d.memberId === p.memberId));
  // 그 사람 차는 주인 없는 차가 된다 (차는 다시 주인을 고르라고 한다)
  db.devices.filter((d) => d.circleId === circle.id && d.ownerId === p.memberId).forEach((d) => (d.ownerId = null));
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 구성원 순서 바꾸기 (차 홈의 얼굴 순서)
route('POST', '/api/circles/:circleId/members/order', guard(async (req, res, p, { circle }) => {
  const ids = (await readBody(req)).ids || [];
  const byId = new Map(circle.members.map((m) => [m.id, m]));
  if (ids.length !== byId.size || !ids.every((id) => byId.has(id))) return send(res, 400, { error: 'ids가 구성원과 맞지 않아요' });
  circle.members = ids.map((id) => byId.get(id));
  saveDb();
  send(res, 200, publicCircle(circle));
}));

// 메시지 -----------------------------------------------------------------

// 서클의 메시지 목록 (차가 내려받아 로컬 매칭)
route('GET', '/api/circles/:circleId/messages', guard((req, res, p, { circle }) => {
  send(res, 200, db.messages.filter((m) => m.circleId === circle.id && !m.hidden).map(publicMessage));
}));

// 메시지 남기기 (폰 웹·차)
route('POST', '/api/circles/:circleId/messages', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req);
  if (!circle.members.some((m) => m.id === b.authorId)) return send(res, 400, { error: '서클 구성원이 아니에요' });
  // 언제 열릴지: 장소(place) 또는 차 상태(rain 비 · night 밤길 · together 옆자리 동승 · door 차에 탈 때)
  const when = b.when || 'place';
  if (!(when in WHEN_LABELS)) return send(res, 400, { error: '열리는 조건을 다시 골라 주세요' });
  const isPlace = when === 'place';
  const hasPlace = WITH_PLACE.includes(when) && typeof b.lat === 'number' && typeof b.lon === 'number';
  if ((isPlace || when === 'region') && !hasPlace) return send(res, 400, { error: '장소를 골라 주세요' });
  if (b.kind !== 'voice' && b.kind !== 'text') return send(res, 400, { error: 'kind는 voice 또는 text' });
  const blocked = containsBlocked(b.text) || containsBlocked(b.placeName);
  if (blocked) return send(res, 422, { error: '부적절한 표현이 있어 남길 수 없어요' });

  const msg = {
    id: newId('msg'),
    circleId: circle.id,
    authorId: b.authorId,
    authorName: memberName(circle, b.authorId),
    when,
    // 차 상태 메시지는 장소가 없으니 조건 이름을 장소 이름 자리에 둔다
    placeName: hasPlace ? (b.placeName || '이름 없는 장소').slice(0, 40) : WHEN_LABELS[when],
    lat: hasPlace ? b.lat : 0,
    lon: hasPlace ? b.lon : 0,
    radiusM: Math.min(Math.max(Number(b.radiusM) || 300, 100), 2000),
    kind: b.kind,
    text: (b.text || '').slice(0, 300),
    audioUrl: null,
    createdAt: new Date().toISOString(),
    hidden: false,
    reports: [],
    ...cleanConditions(b, circle),
  };
  if (b.kind === 'voice') {
    if (!b.audioBase64) return send(res, 400, { error: '녹음이 없어요' });
    const ext = b.audioExt && /^[a-z0-9]{2,4}$/.test(b.audioExt) ? b.audioExt : 'webm';
    // 목소리 파일 주소는 열쇠 없이 열리므로 짐작할 수 없는 이름을 쓴다
    const file = `v_${crypto.randomBytes(12).toString('hex')}.${ext}`;
    fs.writeFileSync(path.join(MEDIA_DIR, file), Buffer.from(b.audioBase64, 'base64'));
    msg.audioUrl = '/media/' + file;
  } else if (!msg.text.trim()) {
    return send(res, 400, { error: '메시지를 써 주세요' });
  }
  db.messages.push(msg);
  saveDb();
  send(res, 201, publicMessage(msg));
}));

// 「들었어요」 (차)
route('POST', '/api/messages/:messageId/heard', guard(async (req, res, p, ctx) => {
  const msg = myMessage(res, ctx, p.messageId);
  if (!msg) return;
  const b = await readBody(req);
  if (!ctx.circle.members.some((m) => m.id === b.memberId)) return send(res, 400, { error: '서클 구성원이 아니에요' });
  // 소식에는 처음 들은 때(at)만 남기고, 다른 차가 오늘 또 틀지 않게 마지막으로 들은 때(lastAt)는 매번 고친다
  const now = new Date().toISOString();
  const carId = ctx.device.kind === 'car' ? ctx.device.id : null;
  const old = db.heard.find((h) => h.messageId === msg.id && h.memberId === b.memberId);
  if (old) { old.lastAt = now; old.lastCarId = carId; }
  else db.heard.push({ messageId: msg.id, memberId: b.memberId, memberName: memberName(ctx.circle, b.memberId), at: now, lastAt: now, carId, lastCarId: carId });
  saveDb();
  send(res, 200, { ok: true });
}, { circleParam: false }));

// 신고 (차·폰)
route('POST', '/api/messages/:messageId/report', guard(async (req, res, p, ctx) => {
  const msg = myMessage(res, ctx, p.messageId);
  if (!msg) return;
  const b = await readBody(req);
  msg.reports.push({ memberId: b.memberId, reason: (b.reason || '').slice(0, 100), at: new Date().toISOString() });
  if (msg.reports.length >= REPORT_HIDE_THRESHOLD) msg.hidden = true;
  saveDb();
  send(res, 200, { ok: true, hidden: msg.hidden });
}, { circleParam: false }));

// 메시지 고치기 (작성자 본인, 글 메시지의 내용·받는 사람)
route('POST', '/api/messages/:messageId/edit', guard(async (req, res, p, ctx) => {
  const msg = myMessage(res, ctx, p.messageId);
  if (!msg) return;
  const b = await readBody(req);
  if (msg.authorId !== b.memberId) return send(res, 403, { error: '본인이 남긴 메시지만 고칠 수 있어요' });
  if (msg.kind === 'voice') {
    // 목소리: 새로 녹음한 것으로 통째로 바꾼다 (예전 녹음 파일은 지운다)
    if (b.audioBase64) {
      const ext = b.audioExt && /^[a-z0-9]{2,4}$/.test(b.audioExt) ? b.audioExt : 'webm';
      const file = `v_${crypto.randomBytes(12).toString('hex')}.${ext}`;
      fs.writeFileSync(path.join(MEDIA_DIR, file), Buffer.from(b.audioBase64, 'base64'));
      removeMedia(msg.audioUrl);
      msg.audioUrl = '/media/' + file;
    }
  } else if (typeof b.text === 'string') {
    const t = b.text.trim().slice(0, 300);
    if (!t) return send(res, 400, { error: '메시지를 써 주세요' });
    msg.text = t;
  }
  if (Array.isArray(b.to)) msg.to = b.to.filter((id) => ctx.circle.members.some((m) => m.id === id));
  msg.editedAt = new Date().toISOString();
  saveDb();
  send(res, 200, { ok: true });
}, { circleParam: false }));

// 메시지 삭제 (작성자 본인)
route('POST', '/api/messages/:messageId/delete', guard(async (req, res, p, ctx) => {
  const msg = myMessage(res, ctx, p.messageId);
  if (!msg) return;
  const b = await readBody(req);
  if (msg.authorId !== b.memberId) return send(res, 403, { error: '본인이 남긴 메시지만 지울 수 있어요' });
  removeMedia(msg.audioUrl);
  db.messages = db.messages.filter((m) => m.id !== msg.id);
  db.heard = db.heard.filter((h) => h.messageId !== msg.id);
  saveDb();
  send(res, 200, { ok: true });
}, { circleParam: false }));

// 일기 · 소식 -------------------------------------------------------------

// 차의 일기 올리기 (차: 본인이 허락한 항목만 담아서 보냄)
route('POST', '/api/circles/:circleId/diaries', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req);
  if (!circle.members.some((m) => m.id === b.memberId)) return send(res, 400, { error: 'not a member' });
  // 날짜는 폰 웹 화면에 그대로 나가므로 형식을 지킨다 (HTML이 들어오면 스크립트가 실행될 수 있다)
  if (b.date !== undefined && !/^\d{4}-\d{2}-\d{2}$/.test(b.date)) return send(res, 400, { error: 'date는 YYYY-MM-DD' });
  const date = b.date || new Date().toISOString().slice(0, 10);
  db.diaries = db.diaries.filter((d) => !(d.circleId === circle.id && d.memberId === b.memberId && d.date === date));
  db.diaries.push({
    circleId: circle.id,
    memberId: b.memberId,
    memberName: memberName(circle, b.memberId),
    date,
    text: (b.text || '').slice(0, 500),
    updatedAt: new Date().toISOString(),
  });
  saveDb();
  send(res, 200, { ok: true });
}));

// 공유 끄기: 내 일기 기록 전부 삭제 (차)
route('POST', '/api/circles/:circleId/diaries/delete', guard(async (req, res, p, { circle }) => {
  const b = await readBody(req);
  db.diaries = db.diaries.filter((d) => !(d.circleId === circle.id && d.memberId === b.memberId));
  saveDb();
  send(res, 200, { ok: true });
}));

// 서클 소식: 「들었어요」와 일기
route('GET', '/api/circles/:circleId/feed', guard((req, res, p, { circle }) => {
  const msgs = new Map(db.messages.filter((m) => m.circleId === circle.id).map((m) => [m.id, m]));
  const carName = (id) => (id && db.devices.find((d) => d.id === id)?.name) || null;
  const heard = db.heard
    .filter((h) => msgs.has(h.messageId))
    .map((h) => {
      const m = msgs.get(h.messageId);
      return { ...h, carName: carName(h.carId), placeName: m.placeName, when: m.when || 'place', authorId: m.authorId, authorName: m.authorName };
    });
  const diaries = db.diaries.filter((d) => d.circleId === circle.id);
  send(res, 200, { heard, diaries });
}));

// 시연용 (PROD=1이면 막힘) --------------------------------------------------

// 기계음 대신 읽어 주기: 에뮬레이터처럼 차에 음성 엔진이 없을 때, 이 PC(Windows)의 한국어 음성으로 글을 읽어 wav로 준다.
// 같은 글은 한 번만 만든다. 실제 서비스(PROD)에서는 쓰지 않는다 (실차는 Pleos TTS로 읽는다).
const TTS_DIR = path.join(DATA_DIR, 'tts');
route('GET', '/api/tts', guard(async (req, res) => {
  if (PROD || process.platform !== 'win32') return send(res, 404, { error: 'not found' });
  const text = String(new URL(req.url, 'http://x').searchParams.get('text') || '').slice(0, 300).trim();
  if (!text) return send(res, 400, { error: 'text' });
  fs.mkdirSync(TTS_DIR, { recursive: true });
  const file = path.join(TTS_DIR, crypto.createHash('sha256').update(text).digest('hex').slice(0, 24) + '.wav');
  if (!fs.existsSync(file)) {
    const txt = file + '.txt';
    fs.writeFileSync(txt, text, 'utf8');
    const ps = "Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer;" +
      "$v=$s.GetInstalledVoices()|?{$_.VoiceInfo.Culture.Name -eq 'ko-KR'}|select -First 1; if($v){$s.SelectVoice($v.VoiceInfo.Name)};" +
      `$s.SetOutputToWaveFile('${file}'); $s.Speak([IO.File]::ReadAllText('${txt}',[Text.Encoding]::UTF8)); $s.Dispose()`;
    await new Promise((ok) => require('child_process').execFile('powershell.exe', ['-NoProfile', '-Command', ps], { timeout: 20000 }, () => ok()));
    fs.rmSync(txt, { force: true });
    if (!fs.existsSync(file)) return send(res, 500, { error: '읽지 못했어요' });
  }
  serveFile(res, file);
}, { circleParam: false }));

// 데이터 초기화: 시드로 되돌린다. 모든 기기 열쇠도 없어진다.
route('POST', '/api/dev/reset', (req, res) => {
  if (PROD) return send(res, 404, { error: 'not found' });
  const name = new URL(req.url, 'http://x').searchParams.get('seed');
  const file = name ? seedFile(name) : SEED_FILE;
  if (!fs.existsSync(file)) return send(res, 404, { error: 'seed not found' });
  fs.copyFileSync(file, DB_FILE);
  db = loadDb();
  send(res, 200, { ok: true });
});

// 서버 -------------------------------------------------------------------

http
  .createServer(async (req, res) => {
    // 깨진 주소(%E0%A4%A 같은)는 decodeURIComponent가 예외를 던진다. try 밖에서 던지면 서버가 통째로 꺼진다.
    let pathname;
    try {
      pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    } catch (e) {
      return send(res, 400, { error: 'bad url' });
    }
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET,POST',
        'Access-Control-Allow-Headers': 'Content-Type, X-Key',
      });
      return res.end();
    }
    try {
      for (const r of routes) {
        const m = req.method === r.method && pathname.match(r.re);
        if (m) {
          const params = {};
          r.keys.forEach((k, i) => (params[k] = m[i + 1]));
          return await r.handler(req, res, params);
        }
      }
      if (req.method === 'GET' && pathname.startsWith('/media/')) {
        return serveFile(res, path.join(MEDIA_DIR, path.basename(pathname)));
      }
      if (req.method === 'GET') {
        const file = pathname === '/' ? 'index.html' : path.basename(pathname);
        return serveFile(res, path.join(WEB_DIR, file));
      }
      send(res, 404, { error: 'not found' });
    } catch (e) {
      console.error(e);
      send(res, 500, { error: String(e.message || e) });
    }
  })
  .listen(PORT, () => {
    console.log(`다온 서버: http://localhost:${PORT}  (에뮬레이터에서는 http://10.0.2.2:${PORT})`);
    const demo = db.circles.find((c) => c.id === 'home');
    if (demo && !PROD) console.log(`시연용 모임 「${demo.name}」 초대 링크: http://localhost:${PORT}/?invite=${demo.inviteCode}`);
  });
