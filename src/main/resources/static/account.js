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
    renderProfileCard();
    // Сохраняем имя для лобби/комнат, чтобы оно сразу подхватывалось.
    if (user.displayName) localStorage.setItem('sp_name', user.displayName);

    // ── Выйти ─────────────────────────────────────────────────────
    document.getElementById('logoutBtn').addEventListener('click', () => {
        // Stateless: выход — это просто удаление токена на клиенте.
        spAuth.clear();
        location.href = '/';
    });

    // ── Новая сессия (модалка) ────────────────────────────────────
    document.querySelectorAll('.btn-new-session').forEach(btn => {
        btn.addEventListener('click', openCreateModal);
    });
    setupCreateModal();

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

function openCreateModal() {
    const overlay = document.getElementById('createModal');
    document.getElementById('createName').value = '';   // поле названия всегда пустое
    document.getElementById('createTasks').value = '';
    overlay.classList.remove('hidden');
    setTimeout(() => document.getElementById('createName').focus(), 0);
}

function setupCreateModal() {
    const overlay = document.getElementById('createModal');
    const close = () => overlay.classList.add('hidden');
    document.getElementById('createCancel').addEventListener('click', close);
    // Клик мимо окна НЕ закрывает модалку (чтобы случайно не потерять введённые
    // задачи). Закрытие — только кнопкой «Отмена» или клавишей Esc.
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !overlay.classList.contains('hidden')) close();
    });
    document.getElementById('createConfirm').addEventListener('click', submitCreate);
}

async function submitCreate() {
    const btn = document.getElementById('createConfirm');
    const roomName = document.getElementById('createName').value.trim() || 'Новая сессия';
    const tasks = document.getElementById('createTasks').value
        .split('\n').map(s => s.trim()).filter(s => s.length > 0);

    btn.disabled = true;
    const prev = btn.textContent;
    btn.textContent = '…';
    try {
        const res = await spAuth.fetch('/api/me/rooms', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: roomName, deck: 'FIBONACCI', tasks })
        });
        if (!res.ok) throw new Error('create failed');
        const { roomId } = await res.json();
        // Сохраняем имя, чтобы лобби подхватило → сразу войдём как ведущий
        localStorage.setItem('sp_name', currentUser().displayName || 'Модератор');
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
        // Кнопка из пустого состояния тоже открывает модалку
        grid.querySelector('.btn-new-session').addEventListener('click', openCreateModal);
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

    const tasksHtml = s.taskCount > 0
        ? `<div class="sess-tasks">
               <div class="sess-tasks-row">
                   <span class="sess-tasks-label">Задачи оценены</span>
                   <span class="sess-tasks-count">${s.estimatedCount}/${s.taskCount}</span>
               </div>
               <div class="progress-track">
                   <div class="progress-fill ${done ? '' : 'partial'}" style="width:${pct}%"></div>
               </div>
           </div>`
        : `<div class="sess-tasks"><span class="sess-tasks-label">Задач пока нет</span></div>`;

    el.innerHTML = `
        <div class="sess-head">
            ${s.alive
                ? `<a class="sess-name" href="${esc(inviteUrl)}&host=1">${esc(s.roomName || s.roomId)}</a>`
                : `<span class="sess-name" style="cursor:default">${esc(s.roomName || s.roomId)}</span>`}
            ${statusHtml}
        </div>

        <div class="sess-id">
            <code>${esc(s.roomId)}</code>
            <span class="sess-dot">·</span>
            <span class="sess-people">👥 ${s.participantCount}</span>
            <span class="sess-dot">·</span>
            <span class="sess-date">${fmtDate(s.lastActiveAt)}</span>
        </div>

        ${tasksHtml}

        <div class="sess-actions">
            ${s.alive
                ? `<a class="sess-enter" href="${esc(inviteUrl)}&host=1">Войти в сессию</a>`
                : ''}
            <button class="act-btn" data-copy="${esc(inviteUrl)}" title="Скопировать ссылку-приглашение">🔗</button>
            <a class="act-btn" href="/results?room=${esc(s.roomId)}" target="_blank" rel="noopener" title="Публичная страница итогов">📊</a>
            <button class="act-btn" data-export title="Экспорт отчёта в Excel">⬇</button>
            <button class="act-btn" data-rename title="Переименовать">✎</button>
            <button class="act-btn act-danger" data-delete title="${s.alive ? 'Завершить и удалить' : 'Удалить из истории'}">🗑</button>
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

// Профиль с кнопкой смены отображаемого имени (для входа через Google/GitHub).
function renderProfileCard() {
    const user = _user || {};
    const card = document.getElementById('profileCard');
    const avatarEl = user.avatarUrl
        ? `<img class="profile-avatar" src="${esc(user.avatarUrl)}" alt="" referrerpolicy="no-referrer">`
        : `<div class="profile-avatar-placeholder">${esc((user.displayName || '?')[0].toUpperCase())}</div>`;
    card.innerHTML = `
        ${avatarEl}
        <div class="profile-info">
            <div class="profile-name">
                ${esc(user.displayName || 'Модератор')}
                <button class="profile-name-edit" id="editNameBtn" title="Изменить имя">✎</button>
            </div>
            ${user.email ? `<div class="profile-email">${esc(user.email)}</div>` : ''}
            <span class="profile-provider">${esc(user.provider)}</span>
        </div>
    `;
    document.getElementById('editNameBtn').addEventListener('click', editDisplayName);
}

async function editDisplayName() {
    const name = prompt('Как вас показывать в комнатах?', (_user && _user.displayName) || '');
    if (name == null) return;
    const trimmed = name.trim();
    if (!trimmed) { toast('Имя не может быть пустым'); return; }
    if (trimmed === (_user && _user.displayName)) return;
    try {
        const res = await spAuth.fetch('/api/me', {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ displayName: trimmed })
        });
        if (!res.ok) throw new Error();
        _user = await res.json();
        renderProfileCard();
        localStorage.setItem('sp_name', _user.displayName || trimmed);
        toast('Имя обновлено', true);
    } catch { toast('Не удалось изменить имя'); }
}

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
        const res = await spAuth.fetch('/api/me/sessions/' + encodeURIComponent(s.roomId) + '/report');
        if (res.status === 404) { toast('Данные сессии больше недоступны для экспорта'); return; }
        if (!res.ok) throw new Error();
        const data = await res.json();
        buildReportXlsx(data, s.roomId);
        toast('Excel-отчёт скачан', true);
    } catch { toast('Не удалось экспортировать'); }
}

/** Собирает Excel-отчёт по сессии: лист «Сводка» + лист «Голоса по задачам». */
function buildReportXlsx(data, roomId) {
    const items = data.items || [];
    const isNum = v => v != null && v !== '' && isFinite(parseFloat(v));
    const estimated = items.filter(i => i.estimate != null && i.estimate !== '').length;
    const sumPoints = items.reduce((n, i) => n + (isNum(i.estimate) ? parseFloat(i.estimate) : 0), 0);
    const consensusOf = it => {
        if (!it.votes || it.votes.length === 0) return '';
        const vals = new Set(it.votes.map(v => v.value));
        return vals.size === 1 ? 'Да' : 'Нет';
    };
    const withVotes = items.filter(i => i.votes && i.votes.length);
    const consensusCount = withVotes.filter(i => new Set(i.votes.map(v => v.value)).size === 1).length;
    const consensusRate = withVotes.length ? Math.round(consensusCount / withVotes.length * 100) : 0;
    const totalRevotes = items.reduce((n, i) => n + (i.revotes || 0), 0);
    const avgRevotes = estimated ? Math.round(totalRevotes / estimated * 100) / 100 : 0;

    // ── Лист «Сводка» ──
    const summary = [
        ['Сессия', data.roomName || roomId],
        ['Код комнаты', roomId],
        ['Дата выгрузки', new Date().toLocaleString('ru-RU')],
        ['Всего задач', items.length],
        ['Оценено', estimated],
        ['Сумма оценок (числовых)', Math.round(sumPoints * 10) / 10],
        ['Доля консенсуса', consensusRate + '%'],
        ['Сред. переголосований на задачу', avgRevotes],
        [],
        ['№', 'Задача', 'Оценка', 'Переголосований', 'Консенсус', 'Голоса']
    ];
    items.forEach((it, i) => {
        const votesStr = (it.votes || []).map(v => `${v.name}: ${v.value}`).join('; ');
        summary.push([i + 1, it.title, it.estimate || '', it.revotes || 0, consensusOf(it), votesStr]);
    });
    const wsSummary = XLSX.utils.aoa_to_sheet(summary);
    wsSummary['!cols'] = [{ wch: 4 }, { wch: 40 }, { wch: 8 }, { wch: 16 }, { wch: 11 }, { wch: 50 }];

    // ── Лист «Голоса» (по строке на голос) ──
    const votesRows = [['Задача', 'Оценка', 'Участник', 'Голос']];
    items.forEach(it => {
        if (it.votes && it.votes.length) {
            it.votes.forEach(v => votesRows.push([it.title, it.estimate || '', v.name, v.value]));
        } else {
            votesRows.push([it.title, it.estimate || '', '—', '—']);
        }
    });
    const wsVotes = XLSX.utils.aoa_to_sheet(votesRows);
    wsVotes['!cols'] = [{ wch: 40 }, { wch: 8 }, { wch: 24 }, { wch: 10 }];

    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, wsSummary, 'Сводка');
    XLSX.utils.book_append_sheet(wb, wsVotes, 'Голоса');
    XLSX.writeFile(wb, (data.roomName || roomId) + '.xlsx');
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
