"use strict";

// ---- Состояние клиента ----
let stompClient = null;
let roomId = null;
let myId = null;
let myRole = null;
let currentState = null;

// roomId берём из ?room=..., иначе показываем лобби для создания.
const params = new URLSearchParams(location.search);
roomId = params.get("room");

const $ = (id) => document.getElementById(id);

// ---- Лобби ----
$("createBtn").addEventListener("click", async () => {
    const name = $("nameInput").value.trim();
    if (!name) { showLobbyError("Введите имя"); return; }
    try {
        const res = await fetch("/api/rooms", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ name: name + "'s room" })
        });
        const data = await res.json();
        roomId = data.roomId;
        history.replaceState(null, "", "?room=" + roomId);
        connectAndJoin(name, $("roleSelect").value);
    } catch (e) {
        showLobbyError("Не удалось создать комнату");
    }
});

// Если зашли по ссылке с ?room=, всё равно показываем форму имени, затем join.
if (roomId) {
    $("createBtn").textContent = "Войти в комнату";
    $("createBtn").onclick = null;
    $("createBtn").addEventListener("click", () => {
        const name = $("nameInput").value.trim();
        if (!name) { showLobbyError("Введите имя"); return; }
        connectAndJoin(name, $("roleSelect").value);
    });
}

function showLobbyError(msg) { $("lobbyError").textContent = msg; }

// ---- Подключение по WebSocket/STOMP ----
function connectAndJoin(name, role) {
    stompClient = new StompJs.Client({
        webSocketFactory: () => new SockJS("/ws"),
        reconnectDelay: 3000,
        onConnect: () => {
            // Персональные сообщения (мой id, ошибки)
            stompClient.subscribe("/user/queue/me", (m) => {
                const body = JSON.parse(m.body);
                myId = body.participantId;
                myRole = body.role;
                toggleModeratorPanel();
            });
            stompClient.subscribe("/user/queue/errors", (m) => {
                showRoomError(JSON.parse(m.body).error);
            });
            // Состояние комнаты
            stompClient.subscribe("/topic/room/" + roomId, (m) => {
                render(JSON.parse(m.body));
            });
            // Отправляем join
            stompClient.publish({
                destination: "/app/room/" + roomId + "/join",
                body: JSON.stringify({ name, role })
            });
            $("lobby").classList.add("hidden");
            $("room").classList.remove("hidden");
        }
    });
    stompClient.activate();
}

// ---- Рендеринг ----
function render(state) {
    currentState = state;
    $("roomName").textContent = state.roomName;
    $("storyLabel").textContent = state.currentStory ? "Оцениваем: " + state.currentStory : "Задача не задана";
    const online = state.participants.filter(p => p.online).length;
    $("onlineCount").textContent = "👥 " + online;

    renderTable(state);
    renderDeck(state);
    renderStats(state);
    toggleModeratorPanel();
}

function renderTable(state) {
    const table = $("table");
    table.innerHTML = "";
    const amModerator = myRole === "MODERATOR";
    state.participants.forEach(p => {
        const seat = document.createElement("div");
        seat.className = "seat" + (p.online ? "" : " offline");

        let cardClass = "pcard";
        let cardText = "";
        if (state.revealed && p.vote != null) { cardClass += " revealed"; cardText = p.vote; }
        else if (p.hasVoted) { cardClass += " voted"; cardText = "✓"; }

        seat.innerHTML =
            `<div class="pname">${escapeHtml(p.name)}${p.id === myId ? " (вы)" : ""}</div>` +
            `<div class="${cardClass}">${cardText}</div>` +
            `<div class="role">${roleLabel(p.role)}</div>`;

        if (amModerator && p.id !== myId) {
            const kick = document.createElement("button");
            kick.className = "kick";
            kick.textContent = "✕";
            kick.title = "Удалить";
            kick.onclick = () => send("kick", { participantId: myId, targetId: p.id });
            seat.appendChild(kick);
        }
        table.appendChild(seat);
    });
}

function renderDeck(state) {
    const deck = $("deck");
    deck.innerHTML = "";
    if (myRole === "OBSERVER") return; // наблюдатели не голосуют
    const me = state.participants.find(p => p.id === myId);
    state.cards.forEach(card => {
        const btn = document.createElement("button");
        btn.className = "pokercard" + (me && me.hasVoted && me.vote === card ? " selected" : "");
        btn.textContent = card;
        btn.disabled = state.revealed;
        btn.onclick = () => send("vote", { participantId: myId, value: card });
        deck.appendChild(btn);
    });
}

function renderStats(state) {
    const el = $("stats");
    if (!state.revealed || !state.stats) { el.classList.add("hidden"); el.innerHTML = ""; return; }
    el.classList.remove("hidden");
    const s = state.stats;
    let html = "";
    if (s.consensus) html += `<div class="consensus">🎉 Консенсус!</div>`;
    if (s.average != null) html += metric("Среднее", round(s.average));
    if (s.median != null) html += metric("Медиана", round(s.median));
    const dist = Object.entries(s.distribution)
        .map(([k, v]) => `${k}×${v}`).join("  ");
    if (dist) html += `<div class="metric">Распределение<b style="font-size:14px">${dist}</b></div>`;
    el.innerHTML = html;
}

function metric(label, value) {
    return `<div class="metric">${label}<b>${value}</b></div>`;
}

function toggleModeratorPanel() {
    $("moderatorPanel").classList.toggle("hidden", myRole !== "MODERATOR");
}

// ---- Действия модератора ----
$("setStoryBtn").addEventListener("click", () => {
    send("story", { participantId: myId, story: $("storyInput").value });
    $("storyInput").value = "";
});
$("revealBtn").addEventListener("click", () => send("reveal", { participantId: myId }));
$("resetBtn").addEventListener("click", () => send("reset", { participantId: myId }));
$("copyLinkBtn").addEventListener("click", () => {
    navigator.clipboard.writeText(location.origin + "/?room=" + roomId);
    $("copyLinkBtn").textContent = "✓ Скопировано";
    setTimeout(() => $("copyLinkBtn").textContent = "🔗 Скопировать ссылку", 1500);
});

function send(action, payload) {
    if (!stompClient || !stompClient.connected) return;
    stompClient.publish({
        destination: "/app/room/" + roomId + "/" + action,
        body: JSON.stringify(payload)
    });
}

function showRoomError(msg) {
    $("roomError").textContent = msg;
    setTimeout(() => $("roomError").textContent = "", 4000);
}

// ---- Утилиты ----
function roleLabel(r) {
    return { MODERATOR: "модератор", PLAYER: "участник", OBSERVER: "наблюдатель" }[r] || r;
}
function round(n) { return Math.round(n * 10) / 10; }
function escapeHtml(s) {
    return s.replace(/[&<>"']/g, c => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    }[c]));
}
