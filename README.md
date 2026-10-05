# Chat: real-time 1:1 messaging

A small WhatsApp/Telegram-style web app: log in with a username (no password), find a user, and chat in real time with
read receipts and typing indicators. Java 17 + Spring Boot backend,
PostgreSQL storage, vanilla-JS frontend served by the same app.

## Run it

```bash
docker compose up --build
```

Open <http://localhost:8080> in **two browser windows or tabs** (each tab keeps its own user),
log in with a different username in each, search for the other user and start chatting.
To try a second device on the same network, open `http://<your-pc-ip>:8080`.

Stop with `docker compose down` (add `-v` to also wipe the database).

> **Behind a corporate / Zero Trust proxy?** If the Docker build fails with
> `PKIX path building failed`, drop your proxy's root CA into [`certs/`](certs/README.md).

### Run from the IDE instead

```bash
docker compose up -d db      # just Postgres
mvn spring-boot:run          # app on :8080, connects to localhost:5432
mvn test                     # unit tests (no DB needed)
```

## Architecture

```
Browser ──REST (/api/**)──▶ UserController / ConversationController ─┐
   │                                                                ├─▶ PostgreSQL
   └──WebSocket (/ws)────▶ ChatSocketHandler ─▶ MessageRepository ──┘
                                 │
                                 └─▶ ChatRouter ─▶ SessionRegistry (userId → open sockets)
```

* **REST** handles joining (get-or-create a user by name), user search, the conversation list
  and paginated history. Every `/api/**` call except `/api/users/join` carries an `X-User-Id`
  header; the WebSocket passes it as `/ws?userId=…`.
* **WebSocket** carries everything live. Spring is used only for the raw WebSocket transport.
  There is no STOMP, message broker or SockJS. Routing, multi-tab fan-out, acks,
  de-duplication and read receipts are all implemented in `com.example.chat.realtime`.

### Message flow

1. The browser generates a `clientMsgId` and shows the message as pending (🕓).
2. The server validates it and **persists first**, returning the already-stored message if
   `(sender_id, client_msg_id)` was seen before (a UNIQUE constraint backs this up), so a retry
   after a dropped connection never duplicates.
3. The stored message is pushed to every open session of the recipient *and* the sender.
   The sending tab treats it as its ack (✓); the sender's other tabs just display it.
4. When the recipient views the chat it sends `read {peer, upToId}`; the sender's ticks turn blue (✓✓).
5. If the socket drops, the client reconnects with exponential backoff and jitter, re-fetches
   its state over REST, and resends anything still pending.

### WebSocket protocol

| Direction | Frame |
|---|---|
| C → S | `{type:"send", to, body, clientMsgId}` · `{type:"read", peer, upToId}` · `{type:"typing", to}` · `{type:"ping"}` |
| S → C | `{type:"hello", me}` · `{type:"message", message}` · `{type:"read", readerId, senderId, upToId}` · `{type:"typing", from}` · `{type:"pong"}` · `{type:"error", clientMsgId, error}` |

## Known limitations / next steps

* **No authentication.** Identity is a client-supplied user id, so anyone can impersonate any
  user and read their chats. Don't expose the app beyond a trusted network.
* **Single app replica.** `SessionRegistry` is in-memory, so two app containers wouldn't see
  each other's sockets. Scaling out needs a cross-node fan-out (Postgres `LISTEN/NOTIFY` or
  Redis pub/sub) behind `ChatRouter.sendToUser`. Everything else (users, messages) is
  already in the DB.
* There is no rate limiting.
* No "delivered" state (only sent and read), group chats, or media.
