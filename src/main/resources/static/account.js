let _user = null; // профиль, нужен кнопке «Новая сессия» из пустого состояния

(async () => {
    // ── Загрузка профиля ──────────────────────────────────────────
    let user;
    try {
        const res = await spAuth.fetch('/api/me');
        if (!res.ok || !res.url.includes('/api/me')) { location.href = '/login'; return; }
        user = await res.json();
        if (!user || !user.id) throw new Error('empty');
    } catch {
        location.href = '/login';
        return;
    }
    _user = user;

    const card = document.getElementById('profileCard');
    const avatarEl = user.avatarUrl
        ? `<img class="profile-avatar" src="${esc(user.avatarUrl)}" alt="" referrerpolicy="no-referrer">`
        : `<div class="profile-avatar-placeholder">${esc((user.displayName || '?')[0].toUpperCase())}</div>`;

    card.innerHTML = `
        ${avatarEl}
        <div class="profile-info">
            <div class="profile-name">${esc(user.displayName || 'Модератор')}</div>
            ${user.email ? `<div class="profile-email">${esc(user.email)}</div>` : ''}
            <span class="profile-provider">${esc(user.provider)}</span>
        </div>
    `;

    // ── Выйти ─────────────────────────────────────────────────────
    document.getElementById('logoutBtn').addEventListener('click', () => {
        // Stateless: выход — это просто удаление токена на клиенте.
        spAuth.clear();
        location.href = '/';
    });

    // ── Новая сессия ──────────────────────────────────────────────
    document.querySelectorAll('.btn-new-session').forEach(btn => {
        btn.addEventListener('click', () => createSession(btn, user));
    });

    // ── История сессий + live-обновление опросом ──────────────────
    await loadSessions();
    startSessionPolling();
})();

// Интервал опроса (мс). Обновляем «Активна сейчас», число участников и
// прогресс задач без перезагрузки страницы.
const POLL_INTERVAL = 10_000;
let pollTimer = null;
let lastSessionsJson = null;

async function loadSessions() {
    let sessions;
    try {
        const res = await spAuth.fetch('/api/me/sessions');
        sessions = res.ok ? await res.json() : [];
    } catch {
        sessions = [];
    }
    // Пропускаем перерисовку, если ничего не изменилось — без мерцания.
    const json = JSON.stringify(sessions);
    if (json === lastSessionsJson) return;
    lastSessionsJson = json;
    renderSummary(sessions);
    renderSessions(sessions);
}

function startSessionPolling() {
    const tick = () => {
        if (document.visibilityState === 'visible') loadSessions();
    };
    pollTimer = setInterval(tick, POLL_INTERVAL);
    // Мгновенно обновляем при возврате на вкладку.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') loadSessions();
    });
}

async function createSession(btn, user) {
    btn.disabled = true;
    const prev = btn.textContent;
    btn.textContent = '…';
    try {
        const res = await spAuth.fetch('/api/me/rooms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: 'Новая сессия', deck: 'FIBONACCI' })
        });
        if (!res.ok) throw new Error('create failed');
        const { roomId } = await res.json();
        // Сохраняем имя, чтобы лобби подхватило → сразу войдём как ведущий
        localStorage.setItem('sp_name', user.displayName || 'Модератор');
        location.href = '/?room=' + encodeURIComponent(roomId) + '&host=1';
    } catch {
        btn.disabled = false;
        btn.textContent = prev;
        toast('Не удалось создать сессию. Попробуйте ещё раз.');
    }
}

function renderSummary(sessions) {
    const row = document.getElementById('statsRow');
    if (!sessions.length) { row.style.display = 'none'; return; }
    const active = sessions.filter(s => s.alive).length;
    const tasksTotal = sessions.reduce((n, s) => n + (s.taskCount || 0), 0);
    const tasksEst = sessions.reduce((n, s) => n + (s.estimatedCount || 0), 0);

    document.getElementById('statTotal').textContent = sessions.length;
    document.getElementById('statActive').textContent = active;
    document.getElementById('statEstimated').innerHTML =
        `${tasksEst}<span class="stat-sub">/${tasksTotal}</span>`;
    row.style.display = '';
}

function renderSessions(sessions) {
    const grid = document.getElementById('sessionsGrid');
    const hint = document.getElementById('sessionsHint');
    grid.innerHTML = '';

    if (!sessions.length) {
        hint.textContent = '';
        grid.innerHTML = `
            <div class="sessions-empty">
                <strong>Пока нет сессий</strong>
                Создайте комнату — она появится здесь, а вы сразу станете ведущим.
                <div><button class="btn-new-session">+ Новая сессия</button></div>
            </div>`;
        // Кнопка из пустого состояния тоже должна работать
        grid.querySelector('.btn-new-session')
            .addEventListener('click', (e) => createSession(e.currentTarget, currentUser()));
        return;
    }

    hint.textContent = sessions.length === 1 ? '1 сессия' : `${sessions.length} сессий`;

    sessions.forEach(s => grid.appendChild(sessionCard(s)));
}

function sessionCard(s) {
    const el = document.createElement('div');
    el.className = 'sess-card' + (s.alive ? ' live' : '');

    const done = s.taskCount > 0 && s.estimatedCount === s.taskCount;
    const pct = s.taskCount > 0 ? Math.round(s.estimatedCount / s.taskCount * 100) : 0;
    const inviteUrl = location.origin + '/?room=' + encodeURIComponent(s.roomId);

    const statusHtml = s.alive
        ? `<span class="sess-status live">● Активна</span>`
        : `<span class="sess-status done">Завершена</span>`;

    const progressHtml = s.taskCount > 0
        ? `<div class="progress-wrap">
               <div class="metric-mini">
                   <span class="mv">${s.estimatedCount}/${s.taskCount}</span>
                   <span class="ml">задач оценено</span>
               </div>
               <div class="progress-track">
                   <div class="progress-fill ${done ? '' : 'partial'}" style="width:${pct}%"></div>
               </div>
           </div>`
        : `<div class="metric-mini"><span class="mv">—</span><span class="ml">нет задач</span></div>`;

    el.innerHTML = `
        <div class="sess-head">
            ${s.alive
                ? `<a class="sess-name" href="${esc(inviteUrl)}&host=1">${esc(s.roomName || s.roomId)}</a>`
                : `<span class="sess-name" style="cursor:default">${esc(s.roomName || s.roomId)}</span>`}
            ${statusHtml}
        </div>
        <div class="sess-id">
            <code>${esc(s.roomId)}</code>
            <button class="icon-btn" data-copy="${esc(inviteUrl)}" title="Скопировать ссылку-приглашение">⎘ ссылка</button>
        </div>
        <div class="sess-metrics">
            <div class="metric-mini"><span class="mv">${s.participantCount}</span><span class="ml">участников</span></div>
            ${progressHtml}
        </div>
        <div class="sess-foot">
            <span class="sess-date">${fmtDate(s.lastActiveAt)}</span>
            <div class="sess-card-actions">
                <a class="icon-btn" href="/results?room=${esc(s.roomId)}" target="_blank" rel="noopener" title="Публичная страница итогов">📊 Итоги</a>
                <button class="icon-btn" data-export title="Экспорт в CSV">⬇ CSV</button>
                <button class="icon-btn" data-rename title="Переименовать">✎</button>
                <button class="icon-btn icon-danger" data-delete title="${s.alive ? 'Завершить и удалить' : 'Удалить из истории'}">🗑</button>
                ${s.alive
                    ? `<a class="sess-enter" href="${esc(inviteUrl)}&host=1">Войти →</a>`
                    : ''}
            </div>
        </div>
    `;

    const copyBtn = el.querySelector('[data-copy]');
    copyBtn.addEventListener('click', () => {
        navigator.clipboard.writeText(copyBtn.dataset.copy)
            .then(() => toast('Ссылка скопирована — отправьте команде', true))
            .catch(() => toast('Не удалось скопировать'));
    });

    el.querySelector('[data-export]').addEventListener('click', () => exportSession(s));
    el.querySelector('[data-rename]').addEventListener('click', () => renameSession(s));
    el.querySelector('[data-delete]').addEventListener('click', () => deleteSession(s));

    return el;
}

function currentUser() { return _user || { displayName: 'Модератор' }; }

async function renameSession(s) {
    const name = prompt('Новое название сессии:', s.roomName || '');
    if (name == null) return;                 // отмена
    const trimmed = name.trim();
    if (!trimmed) { toast('Название не может быть пустым'); return; }
    if (trimmed === s.roomName) return;
    try {
        const res = await spAuth.fetch('/api/me/sessions/' + encodeURIComponent(s.roomId), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: trimmed })
        });
        if (!res.ok) throw new Error();
        toast('Переименовано', true);
        forceReloadSessions();
    } catch { toast('Не удалось переименовать'); }
}

async function deleteSession(s) {
    const msg = s.alive
        ? `Завершить и удалить сессию «${s.roomName || s.roomId}»? Активные участники потеряют доступ.`
        : `Удалить сессию «${s.roomName || s.roomId}» из истории?`;
    if (!confirm(msg)) return;
    try {
        const res = await spAuth.fetch('/api/me/sessions/' + encodeURIComponent(s.roomId), {
            method: 'DELETE'
        });
        if (!res.ok) throw new Error();
        toast('Удалено', true);
        forceReloadSessions();
    } catch { toast('Не удалось удалить'); }
}

async function exportSession(s) {
    try {
        const res = await spAuth.fetch('/api/me/sessions/' + encodeURIComponent(s.roomId) + '/export');
        if (res.status === 404) { toast('Данные сессии больше недоступны для экспорта'); return; }
        if (!res.ok) throw new Error();
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = (s.roomName || s.roomId) + '.csv';
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        toast('CSV скачан', true);
    } catch { toast('Не удалось экспортировать'); }
}

/** Принудительно перечитать список (сбросив кэш сравнения). */
function forceReloadSessions() {
    lastSessionsJson = null;
    loadSessions();
}

function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleString('ru-RU', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
    });
}

let toastTimer = null;
function toast(msg, success) {
    const t = document.getElementById('toast');
    t.textContent = msg;
    t.classList.toggle('success', !!success);
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}

function esc(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
