"use strict";

const $ = (id) => document.getElementById(id);

// ---------- Состояние ----------
let stompClient = null;
let roomId = new URLSearchParams(location.search).get("room");
let myId = null;
let myRole = null;
let currentState = null;
const joinMode = !!roomId; // true = вход по ссылке, false = создание комнаты

// ---------- Настройка лобби ----------
(function setupLobby() {
    // имя запоминаем между визитами
    const saved = localStorage.getItem("sp_name");
    if (saved) $("nameInput").value = saved;

    if (joinMode) {
        // Вход по ссылке: колоду задаёт создатель, но можно выбрать роль (участник/наблюдатель).
        $("lobbySub").textContent = "Вас пригласили в комнату — введите имя, чтобы войти";
        $("deckField").classList.add("hidden");
        $("roleField").classList.remove("hidden");
        $("primaryBtn").textContent = "Войти в комнату";
    }
    // При создании комнаты роль не спрашиваем — создатель автоматически модератор.

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
            // создаём комнату
            const res = await fetch("/api/rooms", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: name + " · команда", deck: $("deckSelect").value })
            });
            if (!res.ok) throw new Error();
            roomId = (await res.json()).roomId;
            history.replaceState(null, "", "?room=" + roomId);
        } else {
            // проверяем, что комната существует
            const res = await fetch("/api/rooms/" + encodeURIComponent(roomId));
            if (!res.ok) { lobbyError("Комната не найдена или закрыта"); $("primaryBtn").disabled = false; return; }
        }
        connectAndJoin(name, role);
    } catch (e) {
        lobbyError("Не удалось подключиться. Попробуйте ещё раз.");
        $("primaryBtn").disabled = false;
    }
}

function lobbyError(msg) { $("lobbyError").textContent = msg; }

// ---------- WebSocket ----------
function connectAndJoin(name, role) {
    stompClient = new StompJs.Client({
        webSocketFactory: () => new SockJS("/ws"),
        reconnectDelay: 3000,
        onConnect: () => {
            setConn(true);
            stompClient.subscribe("/user/queue/me", (m) => onMe(JSON.parse(m.body)));
            stompClient.subscribe("/topic/room/" + roomId, (m) => render(JSON.parse(m.body)));
            stompClient.publish({
                destination: "/app/room/" + roomId + "/join",
                body: JSON.stringify({ name, role })
            });
        },
        onWebSocketClose: () => setConn(false),
        onStompError: () => setConn(false)
    });
    stompClient.activate();
}

function onMe(body) {
    if (body.error) {
        // вернуться в лобби с ошибкой
        showRoom(false);
        lobbyError(body.error);
        $("primaryBtn").disabled = false;
        if (stompClient) stompClient.deactivate();
        return;
    }
    myId = body.participantId;
    myRole = body.role;
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
    currentState = state;
    if (!myId) return; // ждём свой id

    $("roomName").textContent = state.roomName;
    const story = $("storyLabel");
    if (state.currentStory) { story.textContent = state.currentStory; story.classList.add("active"); }
    else { story.textContent = "Задача не задана"; story.classList.remove("active"); }

    const online = state.participants.filter(p => p.online).length;
    $("onlineCount").textContent = "👥 " + online;

    if ($("deckChange").value !== state.deck) $("deckChange").value = state.deck;

    renderTable(state);
    renderDeck(state);
    renderResults(state);
    renderWaitHint(state);
}

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

function renderDeck(state) {
    const deck = $("deck");
    deck.innerHTML = "";
    if (myRole === "OBSERVER") return;
    const me = state.participants.find(p => p.id === myId);
    state.cards.forEach(card => {
        const btn = document.createElement("button");
        btn.className = "pcard" + (me && me.vote === card ? " selected" : "");
        btn.textContent = card;
        btn.disabled = state.revealed;
        btn.onclick = () => send("vote", { participantId: myId, value: card });
        deck.appendChild(btn);
    });
    $("deckHint").textContent = state.revealed
        ? "Карты вскрыты — ждём нового раунда"
        : "Выберите карту";
}

function renderResults(state) {
    const el = $("results");
    if (!state.revealed || !state.stats) { el.classList.add("hidden"); el.innerHTML = ""; return; }
    el.classList.remove("hidden");
    const s = state.stats;
    let html = "";
    if (s.consensus) html += `<div class="consensus">🎉 Консенсус!</div>`;
    if (s.average != null) html += metric("Среднее", round(s.average));
    if (s.median != null) html += metric("Медиана", round(s.median));
    const chips = Object.entries(s.distribution)
        .sort((a, b) => b[1] - a[1])
        .map(([k, v]) => `<span class="chip">${escapeHtml(k)} <b>×${v}</b></span>`).join("");
    if (chips) html += `<div class="metric"><div class="label">Голоса</div><div class="dist">${chips}</div></div>`;
    el.innerHTML = html;
}

function renderWaitHint(state) {
    const hint = $("waitHint");
    if (state.revealed) { hint.textContent = ""; return; }
    const voters = state.participants.filter(p => p.role !== "OBSERVER");
    const voted = voters.filter(p => p.hasVoted).length;
    hint.textContent = voters.length
        ? `Проголосовали: ${voted} из ${voters.length}`
        : "";
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
});
$("storyInput").addEventListener("keydown", e => { if (e.key === "Enter") $("setStoryBtn").click(); });
$("revealBtn").addEventListener("click", () => send("reveal", { participantId: myId }));
$("resetBtn").addEventListener("click", () => send("reset", { participantId: myId }));
$("deckChange").addEventListener("change", (e) => send("deck", { participantId: myId, deck: e.target.value }));
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
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
