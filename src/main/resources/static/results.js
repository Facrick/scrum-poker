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

    const items = data.items || [];
    const rows = items.map((it, i) => {
        const votes = it.votes || [];
        const consHtml = votes.length
            ? `<span class="cons ${it.consensus ? 'yes' : 'no'}">${it.consensus ? '✓ консенсус' : 'разброс'}</span>`
            : '';
        const votesHtml = votes.length
            ? `<div class="res-votes">${votes.map(v => `<span class="res-vote">${esc(v.name)} <b>${esc(v.value)}</b></span>`).join('')}</div>`
            : '';
        return `
        <div class="res-block">
            <div class="res-row">
                <span class="num">${i + 1}</span>
                <span class="ttl">${esc(it.title)}</span>
                ${consHtml}
                ${it.revotes > 0 ? `<span class="rev" title="Переголосований">↻${it.revotes}</span>` : ''}
                <span class="est ${it.estimate ? '' : 'none'}">${it.estimate ? esc(it.estimate) : '—'}</span>
            </div>
            ${votesHtml}
        </div>`;
    }).join('');

    content.innerHTML = `
        <div class="res-head">
            <div class="res-title">${esc(data.roomName || 'Сессия')}</div>
            <div class="res-sub">Оценено ${data.estimatedCount} из ${data.taskCount} задач</div>
        </div>
        <div class="res-card">
            ${rows || '<div class="res-empty">В этой сессии ещё нет задач.</div>'}
        </div>`;

    // CSV — универсальный отчёт, открывается на любом устройстве/в любой таблице.
    document.getElementById('csvBtn').addEventListener('click', () => downloadCsv(data, roomId));
})();

function downloadCsv(data, roomId) {
    const items = data.items || [];
    const esc = s => {
        const v = String(s ?? '');
        return /[";\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
    };
    const lines = ['№;Задача;Оценка;Переголосований;Консенсус;Голоса'];
    items.forEach((it, i) => {
        const votes = (it.votes || []).map(v => `${v.name}: ${v.value}`).join(', ');
        const cons = (it.votes && it.votes.length) ? (it.consensus ? 'Да' : 'Нет') : '';
        lines.push([i + 1, it.title, it.estimate || '', it.revotes || 0, cons, votes].map(esc).join(';'));
    });
    // BOM, чтобы Excel корректно открыл UTF-8 с кириллицей.
    const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = (data.roomName || roomId) + '.csv';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    toast('CSV скачан', true);
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
