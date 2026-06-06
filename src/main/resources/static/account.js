(async () => {
    let user;
    try {
        const res = await fetch('/api/me');
        // 302 редирект на /login браузер следует автоматически — res.url изменится
        if (!res.ok || !res.url.includes('/api/me')) {
            location.href = '/login';
            return;
        }
        user = await res.json();
        if (!user || !user.id) throw new Error('empty user');
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

    document.getElementById('logoutBtn').addEventListener('click', async () => {
        await fetch('/api/me/logout', { method: 'POST' });
        location.href = '/';
    });
})();

function esc(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
