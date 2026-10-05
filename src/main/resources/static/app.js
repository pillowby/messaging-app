'use strict';

/*
 * Browser client. No framework: the UI is small, and keeping the socket/reconnect logic in
 * plain JS makes the real-time behaviour easy to follow.
 *
 * Source of truth is always the server. The socket pushes live updates; on every
 * (re)connect the client drops its cached threads and re-fetches over REST, because
 * anything sent while the socket was down was never pushed to us.
 */

const PAGE_SIZE = 50;
const PING_MS = 25_000;      // must stay below the server's 70s idle timeout and typical proxy timeouts (~60s)
const TYPING_SEND_MS = 2_000; // throttle: at most one "typing" frame per 2s instead of one per keystroke
const TYPING_SHOW_MS = 3_500; // slightly longer than the throttle so the indicator doesn't flicker

const state = {
    userId: safeStorage('get', 'userId'),
    me: null,
    ws: null,
    wsReady: false,
    retry: 0,
    convos: new Map(),   // partnerId -> {partnerId, partnerName, lastMessage, unread}
    threads: new Map(),  // partnerId -> {messages: Map<id, msg>, hasMore, loading}
    pending: new Map(),  // clientMsgId -> {to, body, clientMsgId, createdAt, error?}
    typing: new Map(),   // userId -> timeout handle
    active: null,        // {id, username}
    lastTypingSent: 0,
};

const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- helpers

function safeStorage(op, key, value) {
    // sessionStorage, not localStorage: localStorage is shared by every tab of the browser, so
    // logging in as Bob in a second window would silently turn the first window into Bob on
    // its next reload, which breaks the "two windows, two users" way this app is tested.
    // sessionStorage is per tab and still survives a reload.
    // Storage access can also throw (private mode, blocked storage); the app should still
    // work then, the user just has to log in again after a reload.
    try {
        if (op === 'get') return sessionStorage.getItem(key);
        if (op === 'set') sessionStorage.setItem(key, value);
        if (op === 'del') sessionStorage.removeItem(key);
    } catch { /* ignore */ }
    return null;
}

/**
 * crypto.randomUUID() only exists in secure contexts (https or localhost). Opening the app
 * from a second device via http://<lan-ip>:8080 is NOT a secure context, so fall back to
 * getRandomValues, which is available everywhere.
 */
function newClientId() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const b = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

function el(tag, props = {}, ...children) {
    // Always build DOM with textContent rather than innerHTML: message bodies are
    // user-controlled, and innerHTML would let one user run script in another's browser.
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
        if (k === 'class') node.className = v;
        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
        else node.setAttribute(k, v);
    }
    for (const c of children) {
        if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
}

function formatTime(iso) {
    const d = new Date(iso);
    const sameDay = d.toDateString() === new Date().toDateString();
    return sameDay
        ? d.toLocaleTimeString([], {hour: '2-digit', minute: '2-digit'})
        : d.toLocaleDateString([], {day: 'numeric', month: 'short'});
}

async function api(path, options = {}) {
    const headers = {'Content-Type': 'application/json'};
    if (state.userId) headers['X-User-Id'] = state.userId;
    const res = await fetch(path, {...options, headers});
    if (!res.ok) {
        let message = res.statusText;
        try { message = (await res.json()).message || message; } catch { /* non-JSON body */ }
        throw Object.assign(new Error(message), {status: res.status});
    }
    return res.status === 204 ? null : res.json();
}

// ---------------------------------------------------------------- identity

$('auth-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    $('auth-error').textContent = '';
    try {
        const user = await api('/api/users/join', {
            method: 'POST',
            body: JSON.stringify({username: $('auth-username').value.trim()}),
        });
        state.userId = String(user.id);
        safeStorage('set', 'userId', state.userId);
        startApp(user);
    } catch (err) {
        $('auth-error').textContent = err.message;
    }
});

$('logout').addEventListener('click', resetSession);

function resetSession() {
    state.userId = null;
    safeStorage('del', 'userId');
    const ws = state.ws;
    state.ws = null; // cleared before close() so onclose knows not to reconnect
    ws?.close();
    Object.assign(state, {me: null, wsReady: false, active: null});
    for (const m of [state.convos, state.threads, state.pending]) m.clear();
    document.body.classList.remove('chat-open');
    $('app').hidden = true;
    $('auth').hidden = false;
}

function startApp(user) {
    state.me = user;
    $('me-name').textContent = user.username;
    $('auth').hidden = true;
    $('app').hidden = false;
    $('thread').hidden = true;
    $('chat-empty').hidden = false;
    connect();
}

// ---------------------------------------------------------------- socket

function connect() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws?userId=${encodeURIComponent(state.userId)}`);
    state.ws = ws;
    ws.onmessage = (e) => onEvent(JSON.parse(e.data));
    ws.onclose = () => {
        if (state.ws !== ws) return; // logged out or replaced; don't resurrect it
        state.wsReady = false;
        $('conn-banner').hidden = false;
        scheduleReconnect();
    };
}

function scheduleReconnect() {
    // Exponential backoff with jitter. When the server restarts every client loses its socket
    // at the same instant; without jitter they would all reconnect in lockstep and hammer it.
    const delay = Math.min(30_000, 1000 * 2 ** state.retry) * (0.5 + Math.random() / 2);
    state.retry++;
    setTimeout(async () => {
        if (!state.userId) return;
        // A rejected handshake (unknown user id, e.g. after a DB reset) looks identical to
        // "server down" from the browser's WebSocket API, as both just close the socket. A REST
        // call can tell them apart: on 400 we go back to the login screen and stop retrying.
        try {
            await api('/api/users/me');
        } catch (err) {
            if (err.status === 400) return resetSession();
        }
        if (state.userId) connect();
    }, delay);
}

setInterval(() => {
    if (state.wsReady) state.ws.send(JSON.stringify({type: 'ping'}));
}, PING_MS);

function wsSend(frame) {
    if (!state.wsReady) return false;
    state.ws.send(JSON.stringify(frame));
    return true;
}

function onEvent(ev) {
    switch (ev.type) {
        case 'hello': return onHello(ev);
        case 'message': return onMessage(ev.message);
        case 'read': return onRead(ev);
        case 'typing': return onTyping(ev);
        case 'error': return onError(ev);
        case 'pong': return;
    }
}

async function onHello(ev) {
    state.wsReady = true;
    state.retry = 0;
    $('conn-banner').hidden = true;
    state.me = ev.me;

    // Catch up on anything we missed while disconnected (see header comment).
    state.threads.clear();
    await loadConversations();
    if (state.active) await openChat(state.active);

    // Resend anything that never got an ack. Safe because the server de-duplicates on
    // clientMsgId, so a message that did get stored before the drop won't be stored twice.
    for (const p of state.pending.values()) {
        if (!p.error) wsSend({type: 'send', to: p.to, body: p.body, clientMsgId: p.clientMsgId});
    }
}

function peerOf(m) {
    return m.senderId === state.me.id ? m.recipientId : m.senderId;
}

function onMessage(m) {
    const mine = m.senderId === state.me.id;
    const peer = peerOf(m);
    if (mine) state.pending.delete(m.clientMsgId); // this is our ack

    const thread = state.threads.get(peer);
    // Server may push the same message twice (idempotent retry); keying by id dedupes it.
    const isNew = !thread?.messages.has(m.id);
    thread?.messages.set(m.id, m);

    const convo = state.convos.get(peer);
    if (!convo) {
        // First message from someone new: we don't know their name yet, so refetch the list.
        loadConversations();
    } else if (!convo.lastMessage || m.id > convo.lastMessage.id) {
        convo.lastMessage = m;
        if (!mine && isNew && !isViewing(peer)) convo.unread++;
    }
    if (!mine) clearTyping(peer);

    if (state.active?.id === peer) {
        renderThread();
        markActiveRead();
    }
    renderConversations();
}

function onRead(ev) {
    if (ev.senderId === state.me.id) {
        // The other side read our messages: turn the ticks blue.
        const thread = state.threads.get(ev.readerId);
        thread?.messages.forEach((m) => {
            if (m.senderId === state.me.id && m.id <= ev.upToId && !m.readAt) m.readAt = new Date().toISOString();
        });
        const c = state.convos.get(ev.readerId);
        if (c?.lastMessage && c.lastMessage.id <= ev.upToId) c.lastMessage.readAt ??= new Date().toISOString();
        if (state.active?.id === ev.readerId) renderThread();
    }
    if (ev.readerId === state.me.id) {
        // We read it in another tab/device; clear the badge here too.
        const c = state.convos.get(ev.senderId);
        if (c && c.lastMessage && c.lastMessage.id <= ev.upToId) c.unread = 0;
    }
    renderConversations();
}

function onTyping(ev) {
    clearTimeout(state.typing.get(ev.from));
    state.typing.set(ev.from, setTimeout(() => clearTyping(ev.from), TYPING_SHOW_MS));
    renderConversations();
    renderPeerStatus();
}

function clearTyping(userId) {
    clearTimeout(state.typing.get(userId));
    if (state.typing.delete(userId)) {
        renderConversations();
        renderPeerStatus();
    }
}

function onError(ev) {
    const p = ev.clientMsgId && state.pending.get(ev.clientMsgId);
    if (p) {
        p.error = ev.error;
        renderThread();
    } else {
        console.warn('Server error:', ev.error);
    }
}

// ---------------------------------------------------------------- data loading

async function loadConversations() {
    const list = await api('/api/conversations');
    state.convos = new Map(list.map((c) => [c.partnerId, c]));
    renderConversations();
}

async function openChat(peer) {
    state.active = peer;
    document.body.classList.add('chat-open');
    $('chat-empty').hidden = true;
    $('thread').hidden = false;
    $('peer-name').textContent = peer.username;
    renderPeerStatus();
    renderConversations();

    if (!state.threads.has(peer.id)) {
        const page = await api(`/api/conversations/${peer.id}/messages?limit=${PAGE_SIZE}`);
        // The user may have clicked another chat while this was loading.
        if (state.active?.id !== peer.id) return;
        state.threads.set(peer.id, {
            messages: new Map(page.map((m) => [m.id, m])),
            hasMore: page.length === PAGE_SIZE,
            loading: false,
        });
    }
    renderThread(true);
    markActiveRead();
    $('composer-input').focus();
}

async function loadOlder() {
    const thread = state.threads.get(state.active?.id);
    if (!thread || !thread.hasMore || thread.loading) return;
    thread.loading = true;
    const oldest = Math.min(...thread.messages.keys());
    const peerId = state.active.id;
    try {
        const page = await api(`/api/conversations/${peerId}/messages?before=${oldest}&limit=${PAGE_SIZE}`);
        page.forEach((m) => thread.messages.set(m.id, m));
        thread.hasMore = page.length === PAGE_SIZE;
    } finally {
        thread.loading = false;
    }
    if (state.active?.id !== peerId) return;
    // Keep the viewport anchored on the message the user was looking at; otherwise
    // prepending content would make the view jump.
    const box = $('messages');
    const fromBottom = box.scrollHeight - box.scrollTop;
    renderThread();
    box.scrollTop = box.scrollHeight - fromBottom;
}

function isViewing(peerId) {
    return state.active?.id === peerId && document.visibilityState === 'visible';
}

function markActiveRead() {
    if (!state.active || !isViewing(state.active.id)) return;
    const thread = state.threads.get(state.active.id);
    if (!thread) return;
    let upTo = -1;
    thread.messages.forEach((m) => {
        if (m.senderId === state.active.id && !m.readAt) {
            upTo = Math.max(upTo, m.id);
            m.readAt = new Date().toISOString();
        }
    });
    const c = state.convos.get(state.active.id);
    if (c) c.unread = 0;
    if (upTo >= 0) wsSend({type: 'read', peer: state.active.id, upToId: upTo});
    renderConversations();
}

// Reading happens when the user actually looks: switching back to the tab counts.
document.addEventListener('visibilitychange', markActiveRead);

// ---------------------------------------------------------------- sending

$('composer').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = $('composer-input');
    const body = input.value.trim();
    if (!body || !state.active) return;
    const p = {to: state.active.id, body, clientMsgId: newClientId(), createdAt: new Date().toISOString()};
    state.pending.set(p.clientMsgId, p);
    // If the socket is down this just stays pending; onHello resends it after reconnecting.
    wsSend({type: 'send', to: p.to, body: p.body, clientMsgId: p.clientMsgId});
    input.value = '';
    autoGrow(input);
    renderThread(true);
});

$('composer-input').addEventListener('keydown', (e) => {
    // Enter sends, Shift+Enter inserts a newline (same as desktop WhatsApp/Telegram).
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        $('composer').requestSubmit();
    }
});

$('composer-input').addEventListener('input', (e) => {
    autoGrow(e.target);
    const now = Date.now();
    if (state.active && now - state.lastTypingSent > TYPING_SEND_MS) {
        state.lastTypingSent = now;
        wsSend({type: 'typing', to: state.active.id});
    }
});

function autoGrow(t) {
    t.style.height = 'auto';
    t.style.height = t.scrollHeight + 'px';
}

// ---------------------------------------------------------------- search

let searchSeq = 0;
$('search').addEventListener('input', async (e) => {
    const q = e.target.value.trim();
    const seq = ++searchSeq;
    if (!q) {
        $('search-results').replaceChildren();
        return;
    }
    const users = await api('/api/users?q=' + encodeURIComponent(q));
    // Responses can arrive out of order; only the latest keystroke's results count.
    if (seq !== searchSeq) return;
    $('search-results').replaceChildren(...users.map((u) =>
        el('li', {
            onclick: () => {
                $('search').value = '';
                $('search-results').replaceChildren();
                openChat(u);
            },
        }, avatar(u.username), el('div', {class: 'convo-main'}, el('div', {class: 'convo-name'}, u.username)))));
});

$('back').addEventListener('click', () => {
    state.active = null;
    document.body.classList.remove('chat-open');
    $('thread').hidden = true;
    $('chat-empty').hidden = false;
    renderConversations();
});

$('messages').addEventListener('scroll', (e) => {
    if (e.target.scrollTop < 60) loadOlder();
});

// ---------------------------------------------------------------- rendering

function avatar(name) {
    return el('div', {class: 'avatar'}, name.slice(0, 1));
}

function renderConversations() {
    const list = [...state.convos.values()].sort((a, b) => b.lastMessage.id - a.lastMessage.id);
    $('no-convos').hidden = list.length > 0;
    $('conversations').replaceChildren(...list.map((c) => {
        const typing = state.typing.has(c.partnerId);
        const last = c.lastMessage;
        const preview = typing ? 'typing…' : (last.senderId === state.me.id ? 'You: ' : '') + last.body;
        return el('li', {
                class: state.active?.id === c.partnerId ? 'active' : '',
                onclick: () => openChat({id: c.partnerId, username: c.partnerName}),
            },
            avatar(c.partnerName),
            el('div', {class: 'convo-main'},
                el('div', {class: 'convo-top'},
                    el('span', {class: 'convo-name'}, c.partnerName),
                    el('span', {class: 'convo-time'}, formatTime(last.createdAt))),
                el('div', {class: 'convo-preview' + (typing ? ' typing' : '')}, preview)),
            c.unread > 0 ? el('span', {class: 'badge'}, c.unread) : null);
    }));
}

function renderPeerStatus() {
    if (!state.active) return;
    const id = state.active.id;
    $('peer-status').textContent = state.typing.has(id) ? 'typing…' : '';
}

function renderThread(forceBottom = false) {
    if (!state.active) return;
    const box = $('messages');
    const nearBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
    const thread = state.threads.get(state.active.id);

    // Ordered by server id, not arrival order: two messages sent at nearly the same time
    // can be pushed out of order, but their DB ids reflect the order they were stored in.
    const stored = thread ? [...thread.messages.values()].sort((a, b) => a.id - b.id) : [];
    const pending = [...state.pending.values()].filter((p) => p.to === state.active.id);

    const nodes = [];
    if (thread?.hasMore) nodes.push(el('div', {class: 'history-hint'}, 'Scroll up for older messages'));
    for (const m of stored) {
        const mine = m.senderId === state.me.id;
        const tick = mine ? el('span', {class: 'tick' + (m.readAt ? ' read' : '')}, m.readAt ? ' ✓✓' : ' ✓') : null;
        nodes.push(el('div', {class: 'msg' + (mine ? ' mine' : '')},
            m.body, el('div', {class: 'msg-meta'}, formatTime(m.createdAt), tick)));
    }
    for (const p of pending) {
        nodes.push(el('div', {
                class: 'msg mine' + (p.error ? ' failed' : ''),
                title: p.error ? 'Click to dismiss' : 'Sending…',
                onclick: () => { if (p.error) { state.pending.delete(p.clientMsgId); renderThread(); } },
            },
            p.body,
            p.error ? el('div', {class: 'msg-error'}, 'Not sent: ' + p.error) : null,
            el('div', {class: 'msg-meta'}, formatTime(p.createdAt), ' 🕓')));
    }
    box.replaceChildren(...nodes);
    if (forceBottom || nearBottom) box.scrollTop = box.scrollHeight;
}

// ---------------------------------------------------------------- boot

(async function boot() {
    if (!state.userId) {
        $('auth').hidden = false;
        return;
    }
    try {
        startApp(await api('/api/users/me'));
    } catch (err) {
        if (err.status === 400) resetSession(); // stored id no longer exists
        else $('auth').hidden = false;
    }
})();
