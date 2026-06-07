// Переключатель светлой/тёмной темы. Начальная тема ставится инлайн-скриптом
// в <head> каждой страницы (без мигания), здесь — кнопки и сохранение выбора.
(function () {
    function current() { return document.documentElement.getAttribute('data-theme') || 'dark'; }

    function apply(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        try { localStorage.setItem('sp_theme', theme); } catch (e) {}
        document.querySelectorAll('.theme-toggle').forEach(function (b) {
            // Показываем иконку ЦЕЛЕВОЙ темы (на что переключим).
            b.textContent = theme === 'light' ? '🌙' : '☀️';
            b.title = theme === 'light' ? 'Тёмная тема' : 'Светлая тема';
            b.setAttribute('aria-label', b.title);
        });
    }

    function wire() {
        document.querySelectorAll('.theme-toggle').forEach(function (b) {
            b.addEventListener('click', function () {
                apply(current() === 'light' ? 'dark' : 'light');
            });
        });
        apply(current()); // выставить иконки под текущую тему
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', wire);
    } else {
        wire();
    }
})();
