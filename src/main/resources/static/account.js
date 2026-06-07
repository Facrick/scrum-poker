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
    const newSessionBtn = document.getElementById('newSessionBtn');
    newSessionBtn.addEventListener('click', async () => {
        newSessionBtn.disabled = true;
        newSessionBtn.textContent = '…';
        try {
            const res = await spAuth.fetch('/api/me/rooms', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: 'Новая сессия', deck: 'FIBONACCI' })
            });
            if (!res.ok) throw new Error('create failed');
            const { roomId } = await res.json();
            // Сохраняем имя, чтобы лобби подхватило → сразу войдём
            localStorage.setItem('sp_name', user.displayName || 'Модератор');
            location.href = '/?room=' + encodeURIComponent(roomId);
        } catch {
            newSessionBtn.disabled = false;
            newSessionBtn.textContent = '+ Новая сессия';
            alert('Не удалось создать сессию. Попробуйте ещё раз.');
        }
    });

    // ── Загрузка истории сессий ───────────────────────────────────
    try {
        const res = await spAuth.fetch('/api/me/sessions');
        const sessions = res.ok ? await res.json() : [];
        renderSessions(sessions);
    } catch {
        renderSessions([]);
    }
})();

function renderSessions(sessions) {
    const skeleton = document.getElementById('sessionsSkeleton');
    const empty    = document.getElementById('sessionsEmpty');
    const table    = document.getElementById('sessionsTable');
    const tbody    = document.getElementById('sessionsBody');

    skeleton.style.display = 'none';

    if (!sessions.length) {
        empty.style.display = '';
        return;
    }

    table.style.display = '';
    tbody.innerHTML = sessions.map(s => {
        const allDone = s.taskCount > 0 && s.estimatedCount === s.taskCount;
        const taskLabel = s.taskCount === 0
            ? '<span class="tag-pill">нет задач</span>'
            : `${s.estimatedCount}/${s.taskCount} оценено ${allDone ? '<span class="tag-pill done">✓</span>' : ''}`;
        const date   = fmtDate(s.lastActiveAt);
        const origin = location.origin;

        const statusCell = s.alive
            ? `<span class="status-alive">Активна</span>
               <br><a href="${origin}/?room=${esc(s.roomId)}" target="_blank"
                       style="font-size:.75rem;color:var(--accent,#2f81f7)">Войти →</a>`
            : `<span class="status-done">Завершена</span>`;

        return `<tr>
            <td><a class="room-link" href="${origin}/?room=${esc(s.roomId)}" target="_blank">${esc(s.roomName || s.roomId)}</a>
                <br><small style="color:var(--text-muted);font-size:.75rem">${esc(s.roomId)}</small></td>
            <td>${statusCell}</td>
            <td>${s.participantCount}</td>
            <td>${taskLabel}</td>
            <td style="color:var(--text-muted);white-space:nowrap">${date}</td>
        </tr>`;
    }).join('');
}

function fmtDate(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleString('ru-RU', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit'
    });
}

function esc(s) {
    return String(s ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
