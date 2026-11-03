// 길 위의 안부 서버 (의존성 없음: node server.js)
// - 서클·메시지·「들었어요」·일기 저장과 전달
// - 키워드 필터, 신고
// - 폰 웹(../web) 정적 제공
// 차의 현재 위치는 받지 않는다. 차는 메시지 장소 목록을 내려받아 차 안에서 근처 여부를 계산한다.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8090);
const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const SEED_FILE = path.join(__dirname, 'seed.json');
const MEDIA_DIR = path.join(DATA_DIR, 'media');
const WEB_DIR = path.join(__dirname, '..', 'web');

// 부적절 내용 필터 (MVP: 키워드). 필요하면 여기에 추가.
const BLOCKED_WORDS = ['시발', '씨발', '병신', '개새끼', '죽어', '꺼져', '미친놈', '미친년'];
// 신고가 이 수 이상이면 숨김
const REPORT_HIDE_THRESHOLD = 1;

fs.mkdirSync(MEDIA_DIR, { recursive: true });

function loadDb() {
  if (!fs.existsSync(DB_FILE)) fs.copyFileSync(SEED_FILE, DB_FILE);
  return JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
}
let db = loadDb();
function saveDb() {
  fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
}

function newId(prefix) {
  return prefix + '_' + crypto.randomBytes(4).toString('hex');
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
};

function serveFile(res, file) {
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) return send(res, 404, { error: 'not found' });
  send(res, 200, fs.readFileSync(file), MIME[path.extname(file)] || 'application/octet-stream');
}

function publicMessage(m) {
  const { reports, ...rest } = m;
  return { when: 'place', ...rest };
}

// 메시지가 열리는 조건. 장소 말고는 차 신호로 판단한다 (판단은 차 안에서).
const WHEN_LABELS = {
  place: '장소',
  rain: '비 오는 날',
  night: '밤길',
  together: '옆자리에 누가 탄 날',
  door: '차에 탈 때',
};

// 라우터 ------------------------------------------------------------------

const routes = [];
function route(method, pattern, handler) {
  const keys = [];
  const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => (keys.push(k), '([^/]+)')) + '$');
  routes.push({ method, re, keys, handler });
}

// 서클 정보
route('GET', '/api/circles/:circleId', (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
  send(res, 200, circle);
});

// 사용자 관리 (차의 설정 화면) ----------------------------------------------

function cleanName(name) {
  return String(name || '').trim().slice(0, 12);
}

// 서클 이름·종류 바꾸기
route('POST', '/api/circles/:circleId/update', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
  const b = await readBody(req);
  if (b.name !== undefined) {
    const name = cleanName(b.name);
    if (!name) return send(res, 400, { error: '이름을 써 주세요' });
    circle.name = name;
  }
  if (b.type !== undefined) {
    if (!['family', 'couple', 'friends'].includes(b.type)) return send(res, 400, { error: 'type은 family, couple, friends' });
    circle.type = b.type;
  }
  saveDb();
  send(res, 200, circle);
});

// 구성원 추가
route('POST', '/api/circles/:circleId/members', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
  const name = cleanName((await readBody(req)).name);
  if (!name) return send(res, 400, { error: '이름을 써 주세요' });
  if (containsBlocked(name)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
  if (circle.members.some((m) => m.name === name)) return send(res, 409, { error: '같은 이름이 이미 있어요' });
  circle.members.push({ id: newId('m'), name, hasCar: true });
  saveDb();
  send(res, 201, circle);
});

// 구성원 이름 바꾸기
route('POST', '/api/circles/:circleId/members/:memberId/rename', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  const member = circle && circle.members.find((m) => m.id === p.memberId);
  if (!member) return send(res, 404, { error: 'member not found' });
  const name = cleanName((await readBody(req)).name);
  if (!name) return send(res, 400, { error: '이름을 써 주세요' });
  if (containsBlocked(name)) return send(res, 422, { error: '쓸 수 없는 이름이에요' });
  member.name = name;
  // 이미 남긴 메시지·기록의 이름도 맞춘다
  db.messages.filter((m) => m.authorId === member.id).forEach((m) => (m.authorName = name));
  db.heard.filter((h) => h.memberId === member.id).forEach((h) => (h.memberName = name));
  db.diaries.filter((d) => d.memberId === member.id).forEach((d) => (d.memberName = name));
  saveDb();
  send(res, 200, circle);
});

// 구성원 빼기: 그 사람이 남긴 메시지·기록도 지운다
route('POST', '/api/circles/:circleId/members/:memberId/delete', (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle || !circle.members.some((m) => m.id === p.memberId)) return send(res, 404, { error: 'member not found' });
  if (circle.members.length <= 1) return send(res, 400, { error: '구성원이 최소 1명은 있어야 해요' });
  circle.members = circle.members.filter((m) => m.id !== p.memberId);
  const gone = new Set(db.messages.filter((m) => m.authorId === p.memberId).map((m) => m.id));
  db.messages = db.messages.filter((m) => !gone.has(m.id));
  db.heard = db.heard.filter((h) => h.memberId !== p.memberId && !gone.has(h.messageId));
  db.diaries = db.diaries.filter((d) => d.memberId !== p.memberId);
  saveDb();
  send(res, 200, circle);
});

// 구성원 순서 바꾸기 (선택 화면에 나오는 순서)
route('POST', '/api/circles/:circleId/members/order', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
  const ids = (await readBody(req)).ids || [];
  const byId = new Map(circle.members.map((m) => [m.id, m]));
  if (ids.length !== byId.size || !ids.every((id) => byId.has(id))) return send(res, 400, { error: 'ids가 구성원과 맞지 않아요' });
  circle.members = ids.map((id) => byId.get(id));
  saveDb();
  send(res, 200, circle);
});

// 서클의 메시지 목록 (차가 내려받아 로컬 매칭)
route('GET', '/api/circles/:circleId/messages', (req, res, p) => {
  const list = db.messages.filter((m) => m.circleId === p.circleId && !m.hidden).map(publicMessage);
  send(res, 200, list);
});

// 메시지 남기기 (폰 웹)
route('POST', '/api/circles/:circleId/messages', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
  const b = await readBody(req);
  if (!circle.members.some((m) => m.id === b.authorId)) return send(res, 400, { error: '서클 구성원이 아니에요' });
  // 언제 열릴지: 장소(place) 또는 차 상태(rain 비 · night 밤길 · together 옆자리 동승 · door 차에 탈 때)
  const when = b.when || 'place';
  if (!(when in WHEN_LABELS)) return send(res, 400, { error: '열리는 조건을 다시 골라 주세요' });
  const isPlace = when === 'place';
  if (isPlace && (typeof b.lat !== 'number' || typeof b.lon !== 'number')) return send(res, 400, { error: '장소를 골라 주세요' });
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
    placeName: isPlace ? (b.placeName || '이름 없는 장소').slice(0, 40) : WHEN_LABELS[when],
    lat: isPlace ? b.lat : 0,
    lon: isPlace ? b.lon : 0,
    radiusM: Math.min(Math.max(Number(b.radiusM) || 300, 100), 2000),
    kind: b.kind,
    text: (b.text || '').slice(0, 300),
    audioUrl: null,
    createdAt: new Date().toISOString(),
    hidden: false,
    reports: [],
  };
  if (b.kind === 'voice') {
    if (!b.audioBase64) return send(res, 400, { error: '녹음이 없어요' });
    const ext = b.audioExt && /^[a-z0-9]{2,4}$/.test(b.audioExt) ? b.audioExt : 'webm';
    const file = msg.id + '.' + ext;
    fs.writeFileSync(path.join(MEDIA_DIR, file), Buffer.from(b.audioBase64, 'base64'));
    msg.audioUrl = '/media/' + file;
  } else if (!msg.text.trim()) {
    return send(res, 400, { error: '메시지를 써 주세요' });
  }
  db.messages.push(msg);
  saveDb();
  send(res, 201, publicMessage(msg));
});

// 「들었어요」 (차)
route('POST', '/api/messages/:messageId/heard', async (req, res, p) => {
  const msg = db.messages.find((m) => m.id === p.messageId);
  if (!msg) return send(res, 404, { error: 'message not found' });
  const b = await readBody(req);
  const circle = db.circles.find((c) => c.id === msg.circleId);
  if (!db.heard.some((h) => h.messageId === msg.id && h.memberId === b.memberId)) {
    db.heard.push({ messageId: msg.id, memberId: b.memberId, memberName: memberName(circle, b.memberId), at: new Date().toISOString() });
    saveDb();
  }
  send(res, 200, { ok: true });
});

// 신고 (차·폰)
route('POST', '/api/messages/:messageId/report', async (req, res, p) => {
  const msg = db.messages.find((m) => m.id === p.messageId);
  if (!msg) return send(res, 404, { error: 'message not found' });
  const b = await readBody(req);
  msg.reports.push({ memberId: b.memberId, reason: (b.reason || '').slice(0, 100), at: new Date().toISOString() });
  if (msg.reports.length >= REPORT_HIDE_THRESHOLD) msg.hidden = true;
  saveDb();
  send(res, 200, { ok: true, hidden: msg.hidden });
});

// 메시지 삭제 (작성자 본인, 폰 웹)
route('POST', '/api/messages/:messageId/delete', async (req, res, p) => {
  const msg = db.messages.find((m) => m.id === p.messageId);
  if (!msg) return send(res, 404, { error: 'message not found' });
  const b = await readBody(req);
  if (msg.authorId !== b.memberId) return send(res, 403, { error: '본인이 남긴 메시지만 지울 수 있어요' });
  db.messages = db.messages.filter((m) => m.id !== msg.id);
  db.heard = db.heard.filter((h) => h.messageId !== msg.id);
  saveDb();
  send(res, 200, { ok: true });
});

// 차의 일기 올리기 (차: 본인이 허락한 항목만 담아서 보냄)
route('POST', '/api/circles/:circleId/diaries', async (req, res, p) => {
  const circle = db.circles.find((c) => c.id === p.circleId);
  if (!circle) return send(res, 404, { error: 'circle not found' });
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
});

// 공유 끄기: 내 일기 기록 전부 삭제 (차)
route('POST', '/api/circles/:circleId/diaries/delete', async (req, res, p) => {
  const b = await readBody(req);
  db.diaries = db.diaries.filter((d) => !(d.circleId === p.circleId && d.memberId === b.memberId));
  saveDb();
  send(res, 200, { ok: true });
});

// 서클 소식 (폰 웹): 「들었어요」와 일기
route('GET', '/api/circles/:circleId/feed', (req, res, p) => {
  const msgIds = new Set(db.messages.filter((m) => m.circleId === p.circleId).map((m) => m.id));
  const heard = db.heard
    .filter((h) => msgIds.has(h.messageId))
    .map((h) => {
      const m = db.messages.find((x) => x.id === h.messageId);
      return { ...h, placeName: m.placeName, when: m.when || 'place', authorId: m.authorId, authorName: m.authorName };
    });
  const diaries = db.diaries.filter((d) => d.circleId === p.circleId);
  send(res, 200, { heard, diaries });
});

// 시연용: 데이터 초기화
route('POST', '/api/dev/reset', (req, res) => {
  fs.copyFileSync(SEED_FILE, DB_FILE);
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
        'Access-Control-Allow-Headers': 'Content-Type',
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
    console.log(`길 위의 안부 서버: http://localhost:${PORT}  (에뮬레이터에서는 http://10.0.2.2:${PORT})`);
  });
