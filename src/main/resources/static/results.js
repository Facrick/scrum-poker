(async () => {
    const roomId = new URLSearchParams(location.search).get('room');
    const content = document.getElementById('content');

    document.getElementById('copyBtn').addEventListener('click', () => {
        navigator.clipboard.writeText(location.href)
            .then(() => toast('Ссылка скопирована', true))
            .catch(() => toast('Не удалось скопировать'));
    });

    if (!roomId) {
        content.innerHTML = `<div class="res-error">Не указана сессия.</div>`;
        return;
    }

    let data;
    try {
        const res = await fetch('/api/public/sessions/' + encodeURIComponent(roomId));
        if (res.status === 404) {
            content.innerHTML = `<div class="res-error">Сессия не найдена или её итоги больше недоступны.</div>`;
            return;
        }
        if (!res.ok) throw new Error();
        data = await res.json();
    } catch {
        content.innerHTML = `<div class="res-error">Не удалось загрузить итоги.</div>`;
        return;
    }

    const rows = (data.items || []).map((it, i) => `
        <div class="res-row">
            <span class="num">${i + 1}</span>
            <span class="ttl">${esc(it.title)}</span>
            ${it.revotes > 0 ? `<span class="rev" title="Переголосований">↻${it.revotes}</span>` : ''}
            <span class="est ${it.estimate ? '' : 'none'}">${it.estimate ? esc(it.estimate) : '—'}</span>
        </div>`).join('');

    content.innerHTML = `
        <div class="res-head">
            <div class="res-title">${esc(data.roomName || 'Сессия')}</div>
            <div class="res-sub">Оценено ${data.estimatedCount} из ${data.taskCount} задач</div>
        </div>
        <div class="res-card">
            ${rows || '<div class="res-empty">В этой сессии ещё нет задач.</div>'}
        </div>`;
})();

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
