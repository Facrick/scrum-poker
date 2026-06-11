"use strict";

const $ = (id) => document.getElementById(id);

// ---------- Состояние ----------
let stompClient = null;
let moderatorUser = null; // профиль, если пользователь залогинен
let roomId = new URLSearchParams(location.search).get("room");
// host=1 ставит ЛК при создании комнаты: создатель должен сразу войти в неё.
const hostMode = new URLSearchParams(location.search).get("host") === "1";
let myId = null;
let myRole = null;
let currentState = null;
let myVote = null;          // локальный выбор карты до вскрытия
let timerInterval = null;   // setInterval для таймера
let backlogOpen = false;    // видимость бэклог-панели
let connectTimeout = null;  // таймаут экрана подключения
let consensusAutoFixed = false; // авто-фиксация оценки при консенсусе (раз за раунд)
let modPanelCollapsed = true;   // на мобиле панель управления свёрнута по умолчанию
let uiLayers = [];              // стек открытых оверлеев (для системной кнопки «Назад»)
let backlogAutoOpened = false; // бэклог уже автооткрывался при входе с задачами
let deckExpandedLayer = false; // есть ли запись истории для развёрнутой колоды (мобайл)
const joinMode = !!roomId;
const mqMobile = window.matchMedia("(max-width: 700px)");
function isMobile() { return mqMobile.matches; }

// ---------- Лобби ----------
let lobbyMode = joinMode ? "join" : "create"; // текущий режим вкладки

function switchLobbyTab(mode) {
    lobbyMode = mode;
    const isJoin = mode === "join";
    $("tabCreate").classList.toggle("active", !isJoin);
    $("tabJoin").classList.toggle("active",   isJoin);
    $("roomNameField").classList.toggle("hidden",  isJoin);
    $("roomCodeField").classList.toggle("hidden",  !isJoin);
    $("roleField").classList.toggle("hidden",      !isJoin);
    $("primaryBtn").textContent = isJoin ? "Войти в сессию" : "Создать комнату";
    $("lobbyError").textContent = "";
}

(function setupLobby() {
    const savedName = localStorage.getItem("sp_name");
    if (savedName) $("nameInput").value = savedName;

    // Тихая проверка авторизации — не блокирует лобби
    spAuth.fetch("/api/me").then(async r => {
        if (!r.ok) return;
        const user = await r.json();
        if (!user?.id) return;
        moderatorUser = user;
        // Предзаполняем имя, если поле ещё пустое
        if (!$("nameInput").value) $("nameInput").value = user.displayName || "";
        // Показываем бадж вошедшего модератора
        const initL = (user.displayName || "M")[0].toUpperCase();
        const av = user.avatarUrl
            ? `<img class="lobby-mod-badge-av" src="${escapeHtml(user.avatarUrl)}" alt="" referrerpolicy="no-referrer">`
            : `<div class="lobby-mod-badge-init">${escapeHtml(initL)}</div>`;
        $("lobbyModBadge").innerHTML =
            `${av}<span class="lobby-mod-badge-name">${escapeHtml(user.displayName || "Модератор")}</span>` +
            `<a href="/account">Кабинет</a>`;
        $("lobbyModBadge").classList.remove("hidden");
    }).catch(() => {});

    // Создание из ЛК (host=1): сразу входим в свежую комнату как модератор,
    // не показывая экран входа. Имя берём из профиля (сохранено в sp_name).
    if (hostMode && roomId && savedName) {
        history.replaceState(null, "", "?room=" + roomId); // убираем host из URL
        connectAndJoin(savedName, "PLAYER");               // сервер сделает первого вошедшего MODERATOR
        return;
    }

    // --- Слушатели всегда регистрируются до return-ов ---
    $("tabCreate").addEventListener("click", () => switchLobbyTab("create"));
    $("tabJoin").addEventListener("click",   () => switchLobbyTab("join"));
    $("primaryBtn").addEventListener("click", onPrimary);
    $("nameInput").addEventListener("keydown",     (e) => { if (e.key === "Enter") onPrimary(); });
    $("roomCodeInput").addEventListener("keydown", (e) => { if (e.key === "Enter") onPrimary(); });

    // Автовосстановление сессии при обновлении страницы (F5)
    const savedPid  = localStorage.getItem("sp_pid");
    const savedRole = localStorage.getItem("sp_role") || "PLAYER";
    if (joinMode && savedName && savedPid) {
        connectAndJoin(savedName, savedRole);
        return;
    }

    // Прямая ссылка: показываем упрощённый экран входа
    if (joinMode) {
        $("lobbyTabs").classList.add("hidden");
        $("roomNameField").classList.add("hidden");
        $("roomCodeField").classList.add("hidden");
        $("roleField").classList.remove("hidden");
        $("primaryBtn").textContent = "Войти в комнату";
        fetch("/api/rooms/" + encodeURIComponent(roomId) + "?t=" + Date.now(), { cache: "no-store" })
            .then(async r => {
                if (!r.ok) { switchToCreateMode("Комната не найдена. Проверьте ссылку или создайте новую:"); return; }
                const info = await r.json();
                const hint = $("lobbyRoomHint");
                hint.textContent = "Вас приглашают в «" + escapeHtml(info.roomName) + "»";
                hint.classList.remove("hidden");
                $("lobbySubtitle").classList.add("hidden");
            })
            .catch(() => {});
        return;
    }

    switchLobbyTab("create");
})();

async function onPrimary() {
    const name = $("nameInput").value.trim();
    if (!name) { lobbyError("Введите имя"); return; }
    localStorage.setItem("sp_name", name);
    const role = $("roleSelect").value;

    $("primaryBtn").disabled = true;
    try {
        if (lobbyMode === "create") {
            const rawRoomName = $("roomNameInput").value.trim();
            const roomName = rawRoomName || "Покер · " + name;
            // spAuth.fetch добавит Authorization: Bearer, если модератор вошёл —
            // иначе сервер не свяжет комнату с владельцем и её не будет в истории ЛК.
            const res = await spAuth.fetch("/api/rooms", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: roomName, deck: "FIBONACCI" })
            });
            if (!res.ok) throw new Error();
            roomId = (await res.json()).roomId;
            // URL обновим после успешного подключения (в onMe)
        } else {
            // join-режим: берём код из поля или из URL
            const code = $("roomCodeInput").value.trim() || roomId;
            if (!code) { lobbyError("Введите код комнаты"); $("primaryBtn").disabled = false; return; }
            roomId = code.toLowerCase();
            const res = await fetch("/api/rooms/" + encodeURIComponent(roomId) + "?t=" + Date.now(), { cache: "no-store" });
            if (!res.ok) {
                switchToCreateMode("Комната не найдена. Проверьте код или создайте новую:");
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

/** Переключает лобби в режим создания комнаты. */
function switchToCreateMode(errorMsg) {
    localStorage.removeItem("sp_pid");
    localStorage.removeItem("sp_role");
    history.replaceState(null, "", "/");
    clearTimeout(connectTimeout);
    $("connecting").classList.add("hidden");
    $("lobby").classList.remove("hidden");
    // Восстанавливаем элементы, скрытые в режиме прямой ссылки
    $("lobbyTabs").classList.remove("hidden");
    $("lobbyRoomHint").classList.add("hidden");
    $("lobbySubtitle").classList.remove("hidden");
    switchLobbyTab("create");
    if (errorMsg) lobbyError(errorMsg);
}

// ---------- Экран подключения ----------
function showConnecting() {
    $("lobby").classList.add("hidden");
    $("room").classList.add("hidden");
    $("connectingText").classList.remove("hidden");
    $("connectingError").textContent = "";
    $("connectingBack").classList.add("hidden");
    $("connecting").classList.remove("hidden");
    // Если за 10с не подключились/не вошли — показываем ошибку и путь назад.
    clearTimeout(connectTimeout);
    connectTimeout = setTimeout(() => {
        if (!myId) {
            $("connectingText").classList.add("hidden");
            $("connectingError").textContent = "Не удалось подключиться к комнате.";
            $("connectingBack").classList.remove("hidden");
        }
    }, 10000);
}

// ---------- WebSocket ----------
function connectAndJoin(name, role) {
    showConnecting();
    stompClient = new StompJs.Client({
        brokerURL: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`,
        reconnectDelay: 3000,
        onConnect: () => {
            setConn(true);
            stompClient.subscribe("/user/queue/me", (m) => onMe(JSON.parse(m.body)));
            stompClient.subscribe("/topic/room/" + roomId, (m) => render(JSON.parse(m.body)));
            // При реконнекте передаём сохранённый participantId для восстановления сессии
            const existingId = localStorage.getItem("sp_pid") || "";
            // token — JWT владельца ЛК (если вошёл): сервер сделает его ведущим в своей комнате.
            const token = (window.spAuth && spAuth.token()) || "";
            stompClient.publish({
                destination: "/app/room/" + roomId + "/join",
                body: JSON.stringify({ name, role, existingId, token })
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
    // Кладём отдельную запись истории «в комнате», чтобы системная кнопка «Назад»
    // (особенно на телефоне) выводила из комнаты на лобби, а не из приложения.
    if (!history.state || !history.state.inRoom) {
        history.pushState({ inRoom: true }, "", "?room=" + roomId);
    }
    showRoom(true);
    applyRole();
    if (currentState) render(currentState);
}

function showRoom(inRoom) {
    if (inRoom) { clearTimeout(connectTimeout); $("connecting").classList.add("hidden"); }
    $("lobby").classList.toggle("hidden", inRoom);
    $("room").classList.toggle("hidden", !inRoom);
    if (inRoom && moderatorUser) {
        const link = $("topbarAccount");
        const initL = (moderatorUser.displayName || "M")[0].toUpperCase();
        link.innerHTML = moderatorUser.avatarUrl
            ? `<img class="topbar-account-av" src="${escapeHtml(moderatorUser.avatarUrl)}" alt="" referrerpolicy="no-referrer">`
            : `<div class="topbar-account-init">${escapeHtml(initL)}</div>`;
        link.classList.remove("hidden");
    }
}

function setConn(online) {
    const dot = $("connDot");
    dot.classList.toggle("online", online);
    dot.classList.toggle("offline", !online);
    dot.title = online ? "В сети" : "Переподключение…";
}

function applyRole() {
    const isMod = myRole === "MODERATOR";
    $("moderatorPanel").classList.toggle("hidden", !isMod);
    $("deckBar").classList.toggle("hidden", myRole === "OBSERVER");
    // Приглашение и шестерёнка управления — только ведущему.
    // Бэклог участник видит в режиме «только чтение» (список задач + оценки):
    // кнопку оставляем, а управление (импорт/удаление/активация/экспорт) скрыто.
    $("copyLinkBtn").classList.toggle("hidden", !isMod);
    $("modToggleBtn").classList.toggle("hidden", !isMod);
    // Сразу прячем поле ввода и кнопку «Добавить списком» у не-ведущего,
    // не дожидаясь первого рендера бэклога.
    $("backlogImport").classList.toggle("hidden", !isMod);
    $("exportCsvBtn").classList.toggle("hidden", !isMod);
    applyModPanel();
}

// На мобиле панель управления свёрнута и открывается кнопкой ⚙.
// На десктопе она всегда развёрнута (collapsed-класс снимается).
function applyModPanel() {
    const panel = $("moderatorPanel");
    if (myRole !== "MODERATOR") { panel.classList.remove("collapsed"); return; }
    const collapsed = isMobile() && modPanelCollapsed;
    panel.classList.toggle("collapsed", collapsed);
    $("modToggleBtn").classList.toggle("active", !collapsed);
}

// ---------- Рендер ----------
function render(state) {
    // Сброс раунда (карты прятались → снова скрыты): забываем локальный выбор.
    if (currentState && currentState.revealed && !state.revealed) {
        myVote = null;
        consensusAutoFixed = false;
        try { localStorage.removeItem(voteKey()); } catch (e) {}
    }
    currentState = state;
    if (!myId) return;

    // Обновляем роль из состояния (нужно при передаче модератора без перезагрузки)
    const meNow = state.participants.find(p => p.id === myId);
    if (meNow && meNow.role !== myRole) {
        myRole = meNow.role;
        applyRole();
    }

    // Автозапоминание оценки: после F5/реконнекта подсвечиваем ранее выбранную
    // карту, пока раунд не вскрыт и мы действительно проголосовали.
    if (myVote == null && !state.revealed) {
        const me = state.participants.find(p => p.id === myId);
        let stored = null;
        try { stored = localStorage.getItem(voteKey()); } catch (e) {}
        if (stored && me && me.hasVoted) myVote = stored;
    }

    $("roomName").textContent = state.roomName;
    $("roomName").classList.toggle("editable", myRole === "MODERATOR");
    $("roomName").title = myRole === "MODERATOR" ? "Нажмите, чтобы переименовать" : state.roomName;
    const codeEl = $("roomCodeBadge");
    if (codeEl) codeEl.textContent = "# " + state.roomId;

    // Кнопка «Забрать роль» — видна владельцу комнаты, когда он не ведущий
    const reclaimBtn = $("reclaimBtn");
    if (reclaimBtn) {
        const canReclaim = state.ownerParticipantId === myId && myRole !== "MODERATOR";
        reclaimBtn.classList.toggle("hidden", !canReclaim);
    }

    const storyText = state.currentStory || "Задача не задана";
    const storyLabel = $("storyLabel");
    storyLabel.textContent = storyText;
    storyLabel.classList.toggle("active", !!state.currentStory);
    // Дублируем текущую задачу в отдельную строку для мобильной версии.
    const storyBar = $("storyBar");
    storyBar.textContent = storyText;
    storyBar.classList.toggle("active", !!state.currentStory);

    const online = state.participants.filter(p => p.online).length;
    $("onlineCount").textContent = online + " онлайн";

    const async = !!state.async;
    // Переключаемся между обычным режимом и async-доской.
    $("stage").classList.toggle("hidden", async);
    $("asyncBoard").classList.toggle("hidden", !async);
    $("deckBar").classList.toggle("hidden", async || myRole === "OBSERVER");
    // На мобиле регистрируем «развёрнутую колоду» как слой истории, чтобы
    // системная «Назад» сворачивала карты (а не выходила из комнаты).
    if (isMobile() && !async && myRole !== "OBSERVER"
        && !deckExpandedLayer && !$("deckBar").classList.contains("collapsed")) {
        expandDeck();
    }
    // Sync-кнопки раунда не нужны в async-режиме
    $("revealBtn").classList.toggle("hidden", async);
    $("resetBtn").classList.toggle("hidden", async);

    renderDeckSelector(state);
    renderTimer(state);
    renderModButtons(state);
    if (async) {
        renderAsyncBoard(state);
    } else {
        renderTable(state);
        renderDeck(state);
        renderResults(state);
        renderWaitHint(state);
    }
    renderBacklog(state);
    updateAsyncToggle(state);
    updateDeckAction(state);
}

// Кнопка «Вскрыть карты» / «Новый раунд» прямо на клавиатуре с оценками —
// чтобы ведущему на мобиле не открывать панель ⚙ ради вскрытия. На десктопе
// она скрыта (там есть панель управления); видимость задаётся CSS.
function updateDeckAction(state) {
    const btn = $("deckActionBtn");
    const show = myRole === "MODERATOR" && !state.async;
    btn.classList.toggle("hidden", !show);
    if (!show) return;
    if (!state.revealed) {
        const anyVoted = state.participants.some(p => p.role !== "OBSERVER" && p.hasVoted);
        btn.textContent = "Вскрыть карты";
        btn.className = "btn btn-primary deck-action";
        btn.disabled = !anyVoted; // хотя бы один проголосовал
        btn.onclick = () => send("reveal", { participantId: myId });
    } else {
        btn.textContent = "Новый раунд";
        btn.className = "btn btn-secondary deck-action";
        btn.disabled = false;
        btn.onclick = () => send("next", { participantId: myId });
    }
}

// Ключ локального хранения выбранной карты (для автозапоминания при F5/реконнекте).
function voteKey() { return "sp_vote_" + roomId; }

// ---------- Async-доска (#3) ----------
function asyncVotesKey() { return "sp_async_" + roomId; }
function loadAsyncVotes() {
    try { return JSON.parse(localStorage.getItem(asyncVotesKey()) || "{}"); } catch { return {}; }
}
function saveAsyncVote(itemId, value) {
    const m = loadAsyncVotes(); m[itemId] = value;
    localStorage.setItem(asyncVotesKey(), JSON.stringify(m));
}

function updateAsyncToggle(state) {
    const btn = $("asyncToggle");
    if (myRole !== "MODERATOR") { btn.classList.add("hidden"); return; }
    btn.classList.remove("hidden");
    btn.textContent = state.async ? "Async: вкл" : "Async: выкл";
    btn.classList.toggle("btn-primary", !!state.async);
    btn.onclick = () => send("async", { participantId: myId, enabled: !state.async });
}

function renderAsyncBoard(state) {
    const board = $("asyncBoard");
    board.innerHTML = "";
    const items = state.backlog || [];
    if (items.length === 0) {
        board.innerHTML = `<div class="async-empty">Бэклог пуст. Добавьте задачи (панель «Бэклог»), и команда сможет голосовать.</div>`;
        return;
    }
    const myVotes = loadAsyncVotes();
    const isMod = myRole === "MODERATOR";
    const isObserver = myRole === "OBSERVER";

    items.forEach(item => {
        const done = !!item.estimate;
        const card = document.createElement("div");
        card.className = "async-card" + (item.itemRevealed ? " revealed" : "") + (done ? " done" : "");

        const status = done ? `<span class="async-status done">Оценка: ${escapeHtml(item.estimate)}</span>`
            : item.itemRevealed ? `<span class="async-status">Вскрыто</span>`
            : `<span class="async-status">${item.voteCount} голос(ов)</span>`;

        let html = `<div class="async-card-head">
            <span class="async-title">${escapeHtml(item.title)}</span>${status}
        </div>`;

        // Голосование участника (пока задача не вскрыта)
        if (!isObserver && !item.itemRevealed) {
            html += `<div class="async-vote-row">` +
                state.cards.map(c => {
                    const picked = myVotes[item.id] === c;
                    return `<button class="av${picked ? " picked" : ""}" data-vote="${escapeHtml(c)}">${escapeHtml(c)}</button>`;
                }).join("") + `</div>`;
        }

        // После вскрытия — кто как проголосовал
        if (item.itemRevealed && item.votes && item.votes.length) {
            html += `<div class="async-votes">` +
                item.votes.map(v => `<span class="async-chip">${escapeHtml(v.name)}<b>${escapeHtml(v.value)}</b></span>`).join("") +
                `</div>`;
        }

        // Управление ведущего
        if (isMod && !done) {
            if (!item.itemRevealed) {
                html += `<div class="async-mod"><button class="btn btn-primary btn-sm" data-reveal>Вскрыть (${item.voteCount})</button></div>`;
            } else {
                html += `<div class="async-mod"><span class="lbl">Зафиксировать:</span>` +
                    state.cards.map(c => `<button class="async-fin" data-fin="${escapeHtml(c)}">${escapeHtml(c)}</button>`).join("") +
                    `</div>`;
            }
        }

        card.innerHTML = html;

        card.querySelectorAll("[data-vote]").forEach(b => b.addEventListener("click", () => {
            const val = b.dataset.vote;
            saveAsyncVote(item.id, val);
            send("backlog/vote", { participantId: myId, itemId: item.id, value: val });
            renderAsyncBoard(state); // мгновенно подсветить выбор
        }));
        const rev = card.querySelector("[data-reveal]");
        if (rev) rev.addEventListener("click", () => send("backlog/reveal", { participantId: myId, itemId: item.id }));
        card.querySelectorAll("[data-fin]").forEach(b => b.addEventListener("click", () =>
            send("backlog/estimate", { participantId: myId, itemId: item.id, value: b.dataset.fin })));

        board.appendChild(card);
    });
}

// ---------- Кнопки модератора ----------
function renderModButtons(state) {
    if (myRole !== "MODERATOR") return;
    // Вскрыть — модератор может в любой момент (хотя бы один проголосовал)
    const anyVoted = state.participants.some(p => p.role !== "OBSERVER" && p.hasVoted);
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
            // Corner pips + center value for revealed seat cards
            const cTL = document.createElement("span");
            cTL.className = "pcard-corner tl";
            cTL.textContent = p.vote;
            const cCenter = document.createElement("span");
            cCenter.className = "pcard-val";
            cCenter.textContent = p.vote;
            cCenter.style.fontSize = cardFontSize(p.vote, 28);
            const cBR = document.createElement("span");
            cBR.className = "pcard-corner br";
            cBR.textContent = p.vote;
            slot.append(cTL, cCenter, cBR);
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

            // Передать права ведущего (вручную) — кроме наблюдателей и тех, кто уже ведущий.
            if (p.role !== "OBSERVER" && p.role !== "MODERATOR") {
                const crown = document.createElement("button");
                crown.className = "promote";
                crown.textContent = "♛";
                crown.title = "Сделать ведущим";
                crown.onclick = () => {
                    if (confirm(`Сделать «${p.name}» ведущим?`)) {
                        send("transfer", { participantId: myId, targetId: p.id });
                    }
                };
                slot.appendChild(crown);
            }
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
        btn.disabled = state.revealed;
        btn.title = card;
        btn.onclick = () => {
            myVote = card;
            try { localStorage.setItem(voteKey(), card); } catch (e) {}
            send("vote", { participantId: myId, value: card });
        };

        // Corner pips (top-left & bottom-right) — like real playing cards
        const cornerTL = document.createElement("span");
        cornerTL.className = "pcard-corner tl";
        cornerTL.textContent = card;

        const cornerBR = document.createElement("span");
        cornerBR.className = "pcard-corner br";
        cornerBR.textContent = card;

        // Center value (larger)
        const center = document.createElement("span");
        center.className = "pcard-val";
        center.textContent = card;
        center.style.fontSize = cardFontSize(card, 22);

        btn.append(cornerTL, center, cornerBR);
        deck.appendChild(btn);
    });
    $("deckHint").textContent = state.revealed
        ? "Карты вскрыты — ждём нового раунда"
        : "Выберите карту";
}

// Аналитика всей сессии (по всему бэклогу): оценено, сумма поинтов,
// доля консенсуса, среднее число переголосований.
function renderSessionStats(state) {
    const box = $("backlogStats");
    const items = (state.backlog || []);
    const estimated = items.filter(i => i.estimate != null && i.estimate !== "");
    if (estimated.length === 0) { box.classList.add("hidden"); box.innerHTML = ""; return; }

    const isNum = v => v != null && v !== "" && isFinite(parseFloat(v));
    const sumPoints = estimated.reduce((n, i) => n + (isNum(i.estimate) ? parseFloat(i.estimate) : 0), 0);
    const withVotes = estimated.filter(i => i.votes && i.votes.length);
    const consensusCount = withVotes.filter(i => new Set(i.votes.map(v => v.value)).size === 1).length;
    const consensusRate = withVotes.length ? Math.round(consensusCount / withVotes.length * 100) : 0;
    const totalRevotes = items.reduce((n, i) => n + (i.revotes || 0), 0);

    box.innerHTML = `
        <div class="backlog-stat"><div class="v">${estimated.length}/${items.length}</div><div class="l">Оценено</div></div>
        <div class="backlog-stat"><div class="v">${Math.round(sumPoints * 10) / 10}</div><div class="l">Σ поинтов</div></div>
        <div class="backlog-stat"><div class="v">${consensusRate}%</div><div class="l">Консенсус</div></div>
        <div class="backlog-stat"><div class="v">${totalRevotes}</div><div class="l">Переголосований</div></div>
    `;
    box.classList.remove("hidden");
}

// ---------- Результаты ----------
function renderResults(state) {
    const el = $("results");
    if (!state.revealed || !state.stats) { el.classList.add("hidden"); el.innerHTML = ""; return; }
    el.classList.remove("hidden");
    const s = state.stats;
    const isMod   = myRole === "MODERATOR";
    const isFixed = state.finalEstimate != null;
    let html = "";

    if (s.consensus) {
        // ── Консенсус ──────────────────────────────────────────────
        const val = Object.keys(s.distribution)[0];
        const isPlain = /[^\x00-\x7F]/.test(val) || isNaN(parseFloat(val));

        // Авто-фиксация: при полном согласии команды оценка проставляется
        // автоматически — ведущему не нужно жать «Зафиксировать».
        if (isMod && !isFixed && !consensusAutoFixed) {
            consensusAutoFixed = true;
            send("estimate", { participantId: myId, estimate: val });
        }

        html += `<div class="consensus-hero">
            <div class="consensus-hero-value${isPlain ? ' plain' : ''}">${escapeHtml(val)}</div>
            <div class="consensus-hero-label">Консенсус</div>
        </div>`;

        if (isFixed) {
            html += `<div class="estimate-locked">✓ Зафиксировано: <strong>${escapeHtml(state.finalEstimate)}</strong></div>`;
        }

        if (isMod) {
            html += `<div class="results-actions">`;
            if (!isFixed) {
                html += `<button id="confirmEstimateBtn" class="btn btn-success" data-val="${escapeHtml(val)}">Зафиксировать</button>`;
                html += `<button id="revoteBtn" class="btn btn-ghost">Переголосовать</button>`;
            } else if (!isMobile()) {
                html += `<button id="consensusResetBtn" class="btn btn-secondary">Новый раунд</button>`;
            }
            html += `</div>`;
        }

    } else {
        // ── Нет консенсуса ─────────────────────────────────────────
        const suggested = s.suggested || (s.average != null ? String(round(s.average)) : null);

        const chips = Object.entries(s.distribution)
            .sort((a, b) => b[1] - a[1])
            .map(([k, v]) => `<span class="vote-chip">
                <span class="vote-chip-val">${escapeHtml(k)}</span>
                <span class="vote-chip-count">×${v}</span>
            </span>`).join("");

        html += `<div class="no-consensus-header">
            <span class="no-consensus-label">Нет консенсуса</span>
            ${s.average != null ? `<span class="no-consensus-avg">среднее <strong>${round(s.average)}</strong></span>` : ""}
        </div>`;

        if (chips) html += `<div class="votes-dist">${chips}</div>`;

        if (isFixed) {
            html += `<div class="estimate-locked">✓ Зафиксировано: <strong>${escapeHtml(state.finalEstimate)}</strong></div>`;
        }

        if (isMod) {
            html += `<div class="results-actions">`;
            if (!isFixed) {
                if (suggested) html += `<button id="confirmEstimateBtn" class="btn btn-success" data-val="${escapeHtml(suggested)}">Принять ${escapeHtml(suggested)}</button>`;
                html += `<button id="revoteBtn" class="btn btn-ghost">Переголосовать</button>`;
            } else {
                html += `<button id="revoteBtn" class="btn btn-ghost">Переголосовать</button>`;
                if (!isMobile()) html += `<button id="consensusResetBtn" class="btn btn-secondary">Новый раунд</button>`;
            }
            html += `</div>`;
        }
    }

    el.innerHTML = html;

    // ── Привязка кнопок ────────────────────────────────────────────
    const confirmBtn = $("confirmEstimateBtn");
    if (confirmBtn) {
        confirmBtn.addEventListener("click", () => {
            const val = confirmBtn.dataset.val;
            if (val) send("estimate", { participantId: myId, estimate: val });
        });
    }
    const revoteBtn = $("revoteBtn");
    if (revoteBtn) revoteBtn.addEventListener("click", () => send("reset", { participantId: myId }));
    const resetBtn2 = $("consensusResetBtn");
    // «Новый раунд» после фиксации оценки — переходим к следующей задаче бэклога
    // (сервер сам откатится к reset, если незаоценённых задач больше нет).
    if (resetBtn2) resetBtn2.addEventListener("click", () => send("next", { participantId: myId }));
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
    // Один раз при входе с уже готовым бэклогом показываем задачи: на десктопе —
    // боковой панелью, на мобиле — выезжающим листом (как слой для «Назад»).
    // Иначе созданная из ЛК сессия с задачами выглядела «пустой».
    if (hasItems && !backlogAutoOpened) {
        backlogAutoOpened = true;
        if (isMobile()) { if (!backlogOpen) openBacklog(); }
        else setBacklogOpen(true);
    }
    panel.classList.toggle("hidden", !backlogOpen);

    // Импорт списком и экспорт — только ведущему; участник видит список «только чтение»
    $("backlogImport").classList.toggle("hidden", myRole !== "MODERATOR");
    $("exportCsvBtn").classList.toggle("hidden", myRole !== "MODERATOR");

    renderSessionStats(state);

    if (!state.backlog) return;
    list.innerHTML = "";
    state.backlog.forEach(item => {
        const el = document.createElement("div");
        const isActive = item.id === state.activeItemId;
        const isDone = !!item.estimate;
        el.className = "backlog-item" + (isActive ? " active" : "") + (isDone ? " done" : "");

        const dot = document.createElement("span");
        dot.className = "backlog-dot";

        const titleEl = document.createElement("span");
        titleEl.className = "backlog-item-title";
        titleEl.textContent = item.title;

        const estEl = document.createElement("span");
        estEl.className = "backlog-item-est";
        estEl.textContent = item.estimate || "";

        el.append(dot, titleEl, estEl);

        // История раунда (#6): значок переголосований + раскрытие голосов.
        const hasHistory = isDone && ((item.votes && item.votes.length) || item.revotes > 0);
        let details = null;
        if (hasHistory) {
            if (item.revotes > 0) {
                const rev = document.createElement("span");
                rev.className = "backlog-revotes";
                rev.title = "Переголосований: " + item.revotes;
                rev.textContent = "↻" + item.revotes;
                el.appendChild(rev);
            }
            const info = document.createElement("button");
            info.className = "backlog-info";
            info.title = "Как голосовали";
            info.textContent = "ⓘ";
            el.appendChild(info);

            details = document.createElement("div");
            details.className = "backlog-votes hidden";
            details.innerHTML = (item.votes || [])
                .map(v => `<span class="backlog-vote-chip">${escapeHtml(v.name)}<b>${escapeHtml(v.value)}</b></span>`)
                .join("") || '<span class="backlog-votes-empty">нет голосов</span>';

            info.onclick = (e) => { e.stopPropagation(); details.classList.toggle("hidden"); };
        }

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
        if (details) list.appendChild(details);
    });
}

function exportCsv() {
    if (!currentState || !currentState.backlog || currentState.backlog.length === 0) {
        toast("Бэклог пуст"); return;
    }
    const items = currentState.backlog;
    const consensusOf = it => (it.votes && it.votes.length)
        ? (new Set(it.votes.map(v => v.value)).size === 1 ? "Да" : "Нет") : "";

    const rows = [["№", "Задача", "Оценка", "Переголосований", "Консенсус", "Голоса"]];
    items.forEach((it, i) => {
        const votes = (it.votes || []).map(v => `${v.name}: ${v.value}`).join("; ");
        rows.push([i + 1, it.title, it.estimate || "", it.revotes || 0, consensusOf(it), votes]);
    });

    const ws = XLSX.utils.aoa_to_sheet(rows);
    ws["!cols"] = [{ wch: 4 }, { wch: 44 }, { wch: 8 }, { wch: 16 }, { wch: 11 }, { wch: 50 }];

    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, "Бэклог");
    XLSX.writeFile(wb, (currentState.roomName || "scrum-poker") + ".xlsx");
    toast("Excel скачан", true);
}

// ---------- Действия ----------
// Переименование сессии «в моменте»: ведущий кликает по названию комнаты.
$("roomName").addEventListener("click", () => {
    if (myRole !== "MODERATOR") return;
    const cur = currentState ? currentState.roomName : "";
    const name = prompt("Новое название сессии:", cur || "");
    if (name == null) return;
    const trimmed = name.trim();
    if (!trimmed || trimmed === cur) return;
    send("rename", { participantId: myId, name: trimmed });
});

$("revealBtn").addEventListener("click", () => send("reveal", { participantId: myId }));
$("resetBtn").addEventListener("click",  () => send("reset",  { participantId: myId }));
$("reclaimBtn").addEventListener("click", () => send("reclaim", { participantId: myId }));

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

// Бэклог — оверлей, закрываемый кнопкой «Назад»
function setBacklogOpen(open) {
    backlogOpen = open;
    $("backlogPanel").classList.toggle("hidden", !open);
}
function openBacklog()  { setBacklogOpen(true);  pushLayer("backlog", () => setBacklogOpen(false)); }
function closeBacklog() { if (!consumeLayer("backlog")) setBacklogOpen(false); }
$("toggleBacklogBtn").addEventListener("click", () => { backlogOpen ? closeBacklog() : openBacklog(); });
$("backlogCloseBtn").addEventListener("click", closeBacklog);

// Панель управления (⚙) — оверлей на мобиле, тоже закрывается «Назад»
function openModMenu()  { modPanelCollapsed = false; applyModPanel(); pushLayer("modmenu", () => { modPanelCollapsed = true; applyModPanel(); }); }
function closeModMenu() { if (!consumeLayer("modmenu")) { modPanelCollapsed = true; applyModPanel(); } }
$("modToggleBtn").addEventListener("click", () => { modPanelCollapsed ? openModMenu() : closeModMenu(); });

// Свернуть/развернуть раскладку оценки (колоду карт).
// На мобиле развёрнутая колода — это слой в истории: системная «Назад» её сворачивает.
function setDeckCollapsed(collapsed) {
    $("deckBar").classList.toggle("collapsed", collapsed);
    $("deckCollapseBtn").textContent = collapsed ? "▴ Показать карты" : "▾ Свернуть карты";
}
function expandDeck() {
    setDeckCollapsed(false);
    if (isMobile() && !deckExpandedLayer) {
        deckExpandedLayer = true;
        pushLayer("deck", () => { deckExpandedLayer = false; setDeckCollapsed(true); });
    }
}
function collapseDeck() {
    if (deckExpandedLayer) consumeLayer("deck"); // через history.back → popstate свернёт
    else setDeckCollapsed(true);
}
$("deckCollapseBtn").addEventListener("click", () => {
    $("deckBar").classList.contains("collapsed") ? expandDeck() : collapseDeck();
});

// При повороте экрана / смене ширины пересобираем мобильную раскладку
mqMobile.addEventListener("change", () => { if (myRole) applyModPanel(); });

$("backlogImportBtn").addEventListener("click", () => {
    const raw = $("backlogImportInput").value;
    const titles = raw.split("\n").map(s => s.trim()).filter(s => s.length > 0);
    if (titles.length === 0) { toast("Вставьте хотя бы одну задачу"); return; }
    if (titles.length > 200) { toast("Не более 200 задач за раз"); return; }
    send("backlog/import", { participantId: myId, titles });
    $("backlogImportInput").value = "";
    toast(`Добавлено задач: ${titles.length}`, true);
});

$("exportCsvBtn").addEventListener("click", exportCsv);

$("copyLinkBtn").addEventListener("click", () => {
    const url = location.origin + "/?room=" + roomId;
    navigator.clipboard.writeText(url).then(() => toast("Ссылка скопирована — отправьте команде", true));
});

// ---------- Выход из комнаты ----------
// Просто покидаем комнату на этом устройстве. Сессия НЕ закрывается, даже если
// выходит ведущий — её можно открыть снова из «Кабинета модератора» или по коду.

// Сбрасываем локальное состояние комнаты (общая часть для выхода и «Назад»).
function clearRoomSession() {
    try { localStorage.removeItem("sp_pid"); } catch (e) {}
    try { localStorage.removeItem("sp_role"); } catch (e) {}
    try { localStorage.removeItem(voteKey()); } catch (e) {}
    if (stompClient) { try { stompClient.deactivate(); } catch (e) {} }
    stompClient = null;
    myId = null;
    currentState = null;
    clearInterval(timerInterval);
    timerInterval = null;
}

// Возврат в лобби БЕЗ перезагрузки страницы — это «действие назад», а не
// навигация. Используется и кнопкой «Выход», и системной кнопкой «Назад».
function returnToLobby() {
    clearRoomSession();
    uiLayers = [];
    backlogAutoOpened = false;
    deckExpandedLayer = false;
    $("room").classList.add("hidden");
    $("connecting").classList.add("hidden");
    $("lobby").classList.remove("hidden");
    // Чистим URL от ?room, чтобы обновление страницы не перезашло в комнату.
    if (location.search) history.replaceState(null, "", "/");
    switchLobbyTab(joinMode ? "join" : "create");
    $("primaryBtn").disabled = false;
}

// ---------- Менеджер оверлеев (бэклог, ⚙-панель) для кнопки «Назад» ----------
// Каждый открытый оверлей добавляет запись в историю. Системная кнопка «Назад»
// сначала закрывает верхний оверлей и только когда всё закрыто — выходит в лобби.
function pushLayer(name, closeFn) {
    uiLayers.push({ name, close: closeFn });
    history.pushState({ inRoom: true, layer: name }, "", "?room=" + roomId);
}
// Закрыть оверлей по имени. Если это верхний слой — уходим через history.back()
// (его закроет popstate), синхронно убирая запись истории. Возвращает true,
// если слой найден и обработан.
function consumeLayer(name) {
    const idx = uiLayers.map(l => l.name).lastIndexOf(name);
    if (idx === -1) return false;
    if (idx === uiLayers.length - 1) { history.back(); return true; }
    uiLayers[idx].close();
    uiLayers.splice(idx, 1);
    return true;
}

$("leaveRoomBtn").addEventListener("click", () => {
    const ask = myRole === "MODERATOR"
        ? "Выйти из комнаты? Сессия останется активной — вы сможете вернуться из кабинета или по коду."
        : "Выйти из комнаты?";
    if (!confirm(ask)) return;
    returnToLobby();
});

// Системная кнопка «Назад»:
//   • если открыт оверлей — закрываем его (остаёмся в комнате);
//   • иначе, если мы в комнате — возвращаемся в лобби (in-app, без перезагрузки).
window.addEventListener("popstate", () => {
    if (uiLayers.length > 0) {
        uiLayers.pop().close();
        return;
    }
    const inRoom = !$("room").classList.contains("hidden");
    if (inRoom) returnToLobby();
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
