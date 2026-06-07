// Общий хелпер аутентификации ЛК: токен JWT в localStorage + заголовок Bearer.
// Stateless — никаких кук/сессий (Railway режет Set-Cookie, теряет in-memory сессии).
(function () {
    const KEY = 'sp_token';

    window.spAuth = {
        token: () => localStorage.getItem(KEY),
        clear: () => localStorage.removeItem(KEY),

        // fetch с автоматическим Authorization: Bearer, если токен есть.
        fetch(url, opts = {}) {
            const t = localStorage.getItem(KEY);
            const headers = new Headers(opts.headers || {});
            if (t) headers.set('Authorization', 'Bearer ' + t);
            return fetch(url, { ...opts, headers });
        }
    };
})();
