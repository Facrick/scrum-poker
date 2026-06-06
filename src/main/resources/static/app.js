"use strict";

const $ = (id) => document.getElementById(id);

// ---------- Состояние ----------
let stompClient = null;
let roomId = new URLSearchParams(location.search).get("room");
let myId = null;
let myRole = null;
let currentState = null;
let myVote = null;          // локальный выбор карты до вскрытия
let timerInterval = null;   // setInterval для таймера
let backlogOpen = false;    // видимость бэклог-панели
const joinMode = !!roomId;

// ---------- Лобби ----------
(function setupLobby() {
    const savedName = localStorage.getItem("sp_name");
    if (savedName) $("nameInput").value = savedName;

    // Автовосстановление сессии при обновлении страницы (F5)
    // Если есть сохранённый participantId и мы в той же комнате — переподключаемся без клика
    const savedPid  = localStorage.getItem("sp_pid");
    const savedRole = localStorage.getItem("sp_role") || "PLAYER";
    if (joinMode && savedName && savedPid) {
        connectAndJoin(savedName, savedRole);
        return; // не показываем лобби, сразу подключаемся
    }

    if (joinMode) {
        $("lobbySub").textContent = "Вас пригласили в комнату — введите имя, чтобы войти";
        $("roleField").classList.remove("hidden");
        $("primaryBtn").textContent = "Войти в комнату";
    } else {
        $("roomNameField").classList.remove("hidden");
    }

    $("primaryBtn").addEventListener("click", onPrimary);
    $("nameInput").addEventListener("keydown", (e) => { if (e.key === "Enter") onPrimary(); });
})();

async function onPrimary() {
    const name = $("nameInput").value.trim();
    if (!name) { lobbyError("Введите имя"); return; }
    localStorage.setItem("sp_name", name);
    const role = $("roleSelect").value;

    $("primaryBtn").disabled = true;
    try {
        if (!joinMode) {
            const rawRoomName = $("roomNameInput").value.trim();
            const roomName = rawRoomName || "Покер · " + name;
            const res = await fetch("/api/rooms", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: roomName, deck: "FIBONACCI" })
            });
            if (!res.ok) throw new Error();
            roomId = (await res.json()).roomId;
            // URL обновим после успешного подключения (в onMe), чтобы при ошибке не застрять в join-режиме
        } else {
            const res = await fetch("/api/rooms/" + encodeURIComponent(roomId));
            if (!res.ok) {
                // Комната не найдена — переключаем в режим создания новой
                switchToCreateMode("Комната закрыта или истекла. Создайте новую:");
                $("primaryBtn").disabled = false;
                return;
            }
        }
        connectAndJoin(name, role);
    } catch (e) {
        lobbyError("Не удалось подключиться. Попробуйте ещё раз.");
        $("primaryBtn").disabled = false;
    }
}

function lobbyError(msg) { $("lobbyError").textContent = msg; }

/** Переключает лобби в режим создания комнаты (убирает join-форму, показывает поле названия). */
function switchToCreateMode(errorMsg) {
    localStorage.removeItem("sp_pid");
    localStorage.removeItem("sp_role");
    history.replaceState(null, "", "/");
    // Переключаем UI на создание
    $("lobbySub").textContent = "Оценка задач командой в реальном времени";
    $("roleField").classList.add("hidden");
    $("roomNameField").classList.remove("hidden");
    $("primaryBtn").textContent = "Создать комнату";
    if (errorMsg) lobbyError(errorMsg);
}

// ---------- WebSocket ----------
function connectAndJoin(name, role) {
    stompClient = new StompJs.Client({
        webSocketFactory: () => new SockJS("/ws"),
        reconnectDelay: 3000,
        onConnect: () => {
            setConn(true);
            stompClient.subscribe("/user/queue/me", (m) => onMe(JSON.parse(m.body)));
            stompClient.subscribe("/topic/room/" + roomId, (m) => render(JSON.parse(m.body)));
            // При реконнекте передаём сохранённый participantId для восстановления сессии
            const existingId = localStorage.getItem("sp_pid") || "";
            stompClient.publish({
                destination: "/app/room/" + roomId + "/join",
                body: JSON.stringify({ name, role, existingId })
            });
        },
        onWebSocketClose: () => setConn(false),
        onStompError: () => setConn(false)
    });
    stompClient.activate();
}

function onMe(body) {
    if (body.error) {
        showRoom(false);
        switchToCreateMode("Комната закрыта или истекла. Создайте новую:");
        $("primaryBtn").disabled = false;
        if (stompClient) stompClient.deactivate();
        return;
    }
    myId = body.participantId;
    myRole = body.role;
    // Сохраняем сессию для восстановления после реконнекта
    localStorage.setItem("sp_pid", myId);
    localStorage.setItem("sp_role", myRole);
    // Обновляем URL только сейчас — соединение успешно, комната точно существует
    if (!joinMode && roomId) {
        history.replaceState(null, "", "?room=" + roomId);
    }
    showRoom(true);
    applyRole();
    if (currentState) render(currentState);
}

function showRoom(inRoom) {
    $("lobby").classList.toggle("hidden", inRoom);
    $("room").classList.toggle("hidden", !inRoom);
}

function setConn(online) {
    const dot = $("connDot");
    dot.classList.toggle("online", online);
    dot.classList.toggle("offline", !online);
    dot.title = online ? "В сети" : "Переподключение…";
}

function applyRole() {
    $("moderatorPanel").classList.toggle("hidden", myRole !== "MODERATOR");
    $("deckBar").classList.toggle("hidden", myRole === "OBSERVER");
}

// ---------- Рендер ----------
function render(state) {
    if (currentState && currentState.revealed && !state.revealed) myVote = null;
    currentState = state;
    if (!myId) return;

    $("roomName").textContent = state.roomName;
    const story = $("storyLabel");
    if (state.currentStory) { story.textContent = state.currentStory; story.classList.add("active"); }
    else { story.textContent = "Задача не задана"; story.classList.remove("active"); }

    const online = state.participants.filter(p => p.online).length;
    $("onlineCount").textContent = "👥 " + online;

    renderDeckSelector(state);
    renderTimer(state);
    renderModButtons(state);
    renderTable(state);
    renderDeck(state);
    renderResults(state);
    renderWaitHint(state);
    renderBacklog(state);
}

// ---------- Кнопки модератора ----------
function renderModButtons(state) {
    if (myRole !== "MODERATOR") return;
    const anyVoted = state.participants.some(p => p.role !== "OBSERVER" && p.hasVoted);
    // Вскрыть — только если кто-то проголосовал и карты ещё не открыты
    $("revealBtn").disabled = state.revealed || !anyVoted;
    // Новый раунд — только если карты вскрыты
    $("resetBtn").disabled = !state.revealed;
}

// ---------- Колода ----------
function renderDeckSelector(state) {
    const sel = $("deckChange");
    if (sel.value !== state.deck) sel.value = state.deck;
    const isCustom = state.deck === "CUSTOM";
    $("customDeckWrap").classList.toggle("hidden", !isCustom);
    if (isCustom && state.cards && state.cards.length > 0) {
        const input = $("customCardsInput");
        if (document.activeElement !== input) input.value = state.cards.join(", ");
    }
}

// ---------- Таймер ----------
function renderTimer(state) {
    const running = state.timerStartedAt && state.timerSeconds > 0 && !state.revealed;

    if (myRole === "MODERATOR") {
        $("startTimerBtn").classList.toggle("hidden", !!running);
        $("stopTimerBtn").classList.toggle("hidden", !running);
    }

    if (running) {
        updateTimerDisplay(state.timerStartedAt, state.timerSeconds);
    } else {
        clearInterval(timerInterval);
        timerInterval = null;
        $("timerDisplay").classList.add("hidden");
    }
}

function updateTimerDisplay(startedAt, seconds) {
    $("timerDisplay").classList.remove("hidden");
    if (timerInterval) clearInterval(timerInterval);

    function tick() {
        const elapsed = (Date.now() - startedAt) / 1000;
        const remaining = Math.max(0, seconds - elapsed);
        const m = Math.floor(remaining / 60);
        const s = Math.floor(remaining % 60);
        $("timerValue").textContent = m + ":" + String(s).padStart(2, "0");
        const display = $("timerDisplay");
        display.classList.toggle("danger", remaining <= 10 && remaining > 0);
        display.classList.toggle("expired", remaining <= 0);

        if (remaining <= 0) {
            clearInterval(timerInterval);
            timerInterval = null;
            // Автовскрытие — только от модератора, один раз
            if (myRole === "MODERATOR" && currentState && !currentState.revealed) {
                send("reveal", { participantId: myId });
            }
        }
    }
    tick();
    timerInterval = setInterval(tick, 500);
}

// ---------- Стол ----------
function renderTable(state) {
    const table = $("table");
    table.innerHTML = "";
    const amMod = myRole === "MODERATOR";

    state.participants.forEach(p => {
        const seat = document.createElement("div");
        seat.className = "seat" + (p.online ? "" : " offline") + (p.id === myId ? " is-me" : "");

        const slot = document.createElement("div");
        slot.className = "card-slot";
        if (p.role === "OBSERVER") {
            slot.classList.add("empty");
            slot.textContent = "👁";
        } else if (state.revealed && p.vote != null) {
            slot.classList.add("revealed");
            slot.textContent = p.vote;
            slot.style.fontSize = cardFontSize(p.vote, 28);
        } else if (p.hasVoted) {
            slot.classList.add("voted");
        } else {
            slot.classList.add("empty");
        }

        const who = document.createElement("div");
        who.className = "who";
        const av = document.createElement("span");
        av.className = "avatar";
        av.style.background = colorFor(p.name);
        av.textContent = initials(p.name);
        const nm = document.createElement("span");
        nm.className = "pname";
        nm.textContent = p.name + (p.id === myId ? " (вы)" : "");
        nm.title = p.name;
        who.append(av, nm);

        if (amMod && p.id !== myId) {
            const kick = document.createElement("button");
            kick.className = "kick";
            kick.textContent = "✕";
            kick.title = "Удалить";
            kick.onclick = () => send("kick", { participantId: myId, targetId: p.id });
            slot.appendChild(kick);
        }

        seat.append(slot, who);
        if (p.role === "MODERATOR") {
            const b = document.createElement("div");
            b.className = "obs-badge";
            b.textContent = "ведущий";
            seat.appendChild(b);
        }
        table.appendChild(seat);
    });
}

// ---------- Колода карт ----------
function renderDeck(state) {
    const deck = $("deck");
    deck.innerHTML = "";
    if (myRole === "OBSERVER") return;
    state.cards.forEach(card => {
        const btn = document.createElement("button");
        const isSelected = state.revealed
            ? (state.participants.find(p => p.id === myId)?.vote === card)
            : (myVote === card);
        btn.className = "pcard" + (isSelected ? " selected" : "");
        btn.textContent = card;
        btn.style.fontSize = cardFontSize(card, 19);
        btn.disabled = state.revealed;
        btn.onclick = () => {
            myVote = card;
            send("vote", { participantId: myId, value: card });
        };
        deck.appendChild(btn);
    });
    $("deckHint").textContent = state.revealed
        ? "Карты вскрыты — ждём нового раунда"
        : "Выберите карту";
}

// ---------- Результаты ----------
function renderResults(state) {
    const el = $("results");
    if (!state.revealed || !state.stats) { el.classList.add("hidden"); el.innerHTML = ""; return; }
    el.classList.remove("hidden");
    const s = state.stats;
    let html = "";

    // Итоговая оценка (зафиксированная) — сверху если есть
    if (state.finalEstimate != null) {
        html += `<div class="final-estimate">✅ Итоговая оценка: <b>${escapeHtml(state.finalEstimate)}</b></div>`;
    }

    // Консенсус — показываем большое число-герой вместо трёх одинаковых метрик
    if (s.consensus) {
        const val = Object.keys(s.distribution)[0];
        const isPlain = /[^\x00-\x7F]/.test(val) || isNaN(parseFloat(val));
        html += `<div class="consensus-hero">
            <div class="consensus-hero-value${isPlain ? ' plain' : ''}">${escapeHtml(val)}</div>
            <div class="consensus-hero-label">🎉 Консенсус!</div>
        </div>`;
        if (myRole === "MODERATOR") {
            html += `<button id="consensusResetBtn" class="btn btn-secondary btn-sm">↩ Новый раунд</button>`;
        }
    } else {
        // Не консенсус — показываем полную статистику
        html += `<div class="results-metrics">`;
        if (s.average != null) html += metric("Среднее", round(s.average));
        if (s.median  != null) html += metric("Медиана", round(s.median));
        const chips = Object.entries(s.distribution)
            .sort((a, b) => b[1] - a[1])
            .map(([k, v]) => `<span class="chip">${escapeHtml(k)} <b>×${v}</b></span>`).join("");
        const votesLabel = s.average == null ? "Распределение голосов" : "Голоса";
        if (chips) html += `<div class="metric"><div class="label">${votesLabel}</div><div class="dist">${chips}</div></div>`;
        html += `</div>`;
    }

    // Кнопка переголосовать — только при не-консенсусе, только модератору
    if (myRole === "MODERATOR" && !s.consensus) {
        html += `<button id="revoteBtn" class="btn btn-ghost btn-sm" style="align-self:center">🔄 Переголосовать</button>`;
    }

    // Форма фиксации оценки для модератора
    if (myRole === "MODERATOR") {
        const suggested = s.consensus
            ? Object.keys(s.distribution)[0]
            : (s.average != null ? String(round(s.average)) : "");
        const current = state.finalEstimate ?? suggested;
        const isFixed = state.finalEstimate != null;
        const btnLabel = isFixed ? "Изменить" : "Зафиксировать";
        const btnClass = isFixed ? "btn-secondary" : "btn-success";
        html += `<div class="estimate-form">
            <input id="estimateInput" class="estimate-input" type="text" maxlength="16"
                   value="${escapeHtml(current)}" placeholder="Оценка" autocomplete="off">
            <button id="confirmEstimateBtn" class="btn ${btnClass} btn-sm">${btnLabel}</button>
        </div>`;
    }

    el.innerHTML = html;

    if (myRole === "MODERATOR") {
        const consensusResetBtn = $("consensusResetBtn");
        if (consensusResetBtn) {
            consensusResetBtn.addEventListener("click", () => send("reset", { participantId: myId }));
        }
        const revoteBtn = $("revoteBtn");
        if (revoteBtn) {
            revoteBtn.addEventListener("click", () => send("reset", { participantId: myId }));
        }
        const confirmBtn = $("confirmEstimateBtn");
        if (confirmBtn) {
            confirmBtn.addEventListener("click", () => {
                const val = $("estimateInput").value.trim();
                if (val) send("estimate", { participantId: myId, estimate: val });
            });
            $("estimateInput").addEventListener("keydown", e => {
                if (e.key === "Enter") confirmBtn.click();
            });
        }
    }
}

// ---------- Прогресс голосования ----------
function renderWaitHint(state) {
    const hint = $("waitHint");
    if (state.revealed) { hint.innerHTML = ""; return; }
    const voters = state.participants.filter(p => p.role !== "OBSERVER");
    const voted  = voters.filter(p => p.hasVoted).length;
    if (!voters.length) { hint.innerHTML = ""; return; }
    const pct = voters.length ? Math.round(voted / voters.length * 100) : 0;
    hint.innerHTML = `
        <div class="vote-progress">
            <div class="vote-progress-track">
                <div class="vote-progress-fill" style="width:${pct}%"></div>
            </div>
            <div class="vote-progress-label">${voted} из ${voters.length} проголосовали</div>
        </div>`;
}

// ---------- Бэклог ----------
function renderBacklog(state) {
    const panel = $("backlogPanel");
    const list = $("backlogList");

    const hasItems = state.backlog && state.backlog.length > 0;
    // Открываем автоматически, если появились задачи и панель ещё не открыта
    if (hasItems && !backlogOpen) {
        backlogOpen = true;
    }
    panel.classList.toggle("hidden", !backlogOpen);

    if (!state.backlog) return;
    list.innerHTML = "";
    state.backlog.forEach(item => {
        const el = document.createElement("div");
        const isActive = item.id === state.activeItemId;
        const isDone = !!item.estimate;
        el.className = "backlog-item" + (isActive ? " active" : "") + (isDone ? " done" : "");

        const titleEl = document.createElement("span");
        titleEl.className = "backlog-item-title";
        titleEl.textContent = item.title;

        const estEl = document.createElement("span");
        estEl.className = "backlog-item-est";
        estEl.textContent = item.estimate || "";

        el.append(titleEl, estEl);

        if (myRole === "MODERATOR") {
            el.title = isActive ? "Текущая задача" : "Активировать";
            el.style.cursor = isActive ? "default" : "pointer";
            if (!isActive) {
                el.onclick = () => send("backlog/activate", { participantId: myId, itemId: item.id });
            }
            const rm = document.createElement("button");
            rm.className = "backlog-remove";
            rm.textContent = "✕";
            rm.title = "Удалить задачу";
            rm.onclick = (e) => {
                e.stopPropagation();
                send("backlog/remove", { participantId: myId, itemId: item.id });
            };
            el.appendChild(rm);
        }
        list.appendChild(el);
    });
}

function exportCsv() {
    if (!currentState || !currentState.backlog || currentState.backlog.length === 0) {
        toast("Бэклог пуст"); return;
    }

    const rows = [["Задача", "Оценка"]];
    currentState.backlog.forEach(item => rows.push([item.title, item.estimate || ""]));

    const ws = XLSX.utils.aoa_to_sheet(rows);

    // Ширина столбцов
    ws["!cols"] = [{ wch: 50 }, { wch: 12 }];

    // Стиль заголовка (жирный)
    ["A1", "B1"].forEach(cell => {
        if (ws[cell]) ws[cell].s = { font: { bold: true } };
    });

    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Бэклог");

    const filename = (currentState.roomName || "scrum-poker") + ".xlsx";
    XLSX.writeFile(wb, filename);
    toast("Excel скачан", true);
}

function metric(label, value) {
    return `<div class="metric"><div class="label">${label}</div><div class="value">${value}</div></div>`;
}

// ---------- Действия ----------
$("setStoryBtn").addEventListener("click", () => {
    const v = $("storyInput").value.trim();
    if (!v) return;
    send("story", { participantId: myId, story: v });
    $("storyInput").value = "";
    $("setStoryBtn").disabled = true;
});
$("storyInput").addEventListener("keydown", e => { if (e.key === "Enter") $("setStoryBtn").click(); });
$("storyInput").addEventListener("input", () => {
    $("setStoryBtn").disabled = !$("storyInput").value.trim();
});
// Изначально заблокирована
$("setStoryBtn").disabled = true;

$("revealBtn").addEventListener("click", () => send("reveal", { participantId: myId }));
$("resetBtn").addEventListener("click", () => send("reset", { participantId: myId }));

$("deckChange").addEventListener("change", (e) => {
    const val = e.target.value;
    if (val === "CUSTOM") {
        $("customDeckWrap").classList.remove("hidden");
        $("customCardsInput").focus();
    } else {
        $("customDeckWrap").classList.add("hidden");
        send("deck", { participantId: myId, deck: val });
    }
});
$("applyCustomDeckBtn").addEventListener("click", applyCustomDeck);
$("customCardsInput").addEventListener("keydown", e => { if (e.key === "Enter") applyCustomDeck(); });
function applyCustomDeck() {
    const raw = $("customCardsInput").value.trim();
    if (!raw) return;
    const cards = raw.split(",").map(s => s.trim()).filter(s => s.length > 0);
    if (cards.length === 0) { toast("Введите хотя бы одну карту"); return; }
    if (cards.length > 20) { toast("Не более 20 карт"); return; }
    send("customdeck", { participantId: myId, cards });
}

// Таймер
$("startTimerBtn").addEventListener("click", () => {
    const seconds = parseInt($("timerSelect").value, 10);
    if (!seconds) { toast("Выберите длительность таймера"); return; }
    send("timer/start", { participantId: myId, seconds });
});
$("stopTimerBtn").addEventListener("click", () => send("timer/stop", { participantId: myId }));

// Бэклог
$("toggleBacklogBtn").addEventListener("click", () => {
    backlogOpen = !backlogOpen;
    $("backlogPanel").classList.toggle("hidden", !backlogOpen);
});

$("exportCsvBtn").addEventListener("click", exportCsv);

$("copyLinkBtn").addEventListener("click", () => {
    const url = location.origin + "/?room=" + roomId;
    navigator.clipboard.writeText(url).then(() => toast("Ссылка скопирована — отправьте команде", true));
});

function send(action, payload) {
    if (!stompClient || !stompClient.connected) { toast("Нет соединения"); return; }
    stompClient.publish({ destination: "/app/room/" + roomId + "/" + action, body: JSON.stringify(payload) });
}

// ---------- Утилиты ----------
let toastTimer = null;
function toast(msg, success) {
    const t = $("toast");
    t.textContent = msg;
    t.classList.toggle("success", !!success);
    t.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("show"), 2600);
}

function initials(name) {
    const parts = name.trim().split(/\s+/);
    return ((parts[0]?.[0] || "") + (parts[1]?.[0] || "")).toUpperCase() || "?";
}
function colorFor(str) {
    let h = 0;
    for (let i = 0; i < str.length; i++) h = str.charCodeAt(i) + ((h << 5) - h);
    return `hsl(${h % 360}, 55%, 50%)`;
}
function round(n) { return Math.round(n * 10) / 10; }
/** Подбирает font-size для карточки в зависимости от длины строки (в px). */
function cardFontSize(val, base) {
    const len = [...val].length; // корректно считает emoji как 1 символ
    if (len <= 2) return base + "px";
    if (len === 3) return Math.round(base * 0.78) + "px";
    return Math.round(base * 0.65) + "px";
}
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}
