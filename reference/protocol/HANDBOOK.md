# The Bale Client Handbook

**A beginner-friendly guide to what this project is, how every piece works, and how to
write your own Bale client in any language.**

You do not need to know anything about protocols, protobuf or WebSockets before
reading this. Part 1 teaches those from zero. Part 2 describes Bale specifically.
Part 3 is a build plan with working code in Python, Java, Kotlin and C#. The
appendices are lookup tables you will keep coming back to.

> **What this handbook is attached to.** A complete, working Python client —
> `baleclient/` — that logs into a real account, lists chats, reads and sends
> messages, uploads photos and files, shows typing indicators, searches, and
> reacts. Every number below was read out of the shipped web client; every path
> this client uses has been exercised against the live server.

### How sure is each part?

Worth knowing before you trust a page:

| Area | Confidence | Evidence |
|---|---|---|
| envelope, handshake, ping, index correlation | **certain** | byte-for-byte verified, 10/10 selftest |
| login, dialogs, history, send, read | **certain** | used live, fields cross-checked against `out/bale.proto` |
| users/groups/avatars, typing, reactions, edit/delete/forward | **high** | extracted + exercised live |
| photo/file upload (single PUT) | **high** | worked live; the *chunked* branch has never run |
| search condition trees, some admin calls | **medium** | schema is exact, argument semantics inferred |

---

# Contents

- [Part 0 — The 30-second version](#part-0--the-30-second-version)
- [Part 1 — Concepts from zero](#part-1--concepts-from-zero)
- [Part 2 — How Bale actually works](#part-2--how-bale-actually-works)
- [Part 3 — Write your own client](#part-3--write-your-own-client)
- [Part 4 — Making it feel like a real messenger](#part-4--making-it-feel-like-a-real-messenger)
- [Part 5 — Debugging playbook](#part-5--debugging-playbook)
- [Part 6 — Testing without an account](#part-6--testing-without-an-account)
- [Part 7 — Rules, ethics, safety](#part-7--rules-ethics-safety)
- [Appendix A — Field cheat sheet](#appendix-a--field-cheat-sheet)
- [Appendix B — Service map](#appendix-b--service-map)
- [Appendix C — Glossary](#appendix-c--glossary)
- [Appendix D — Where everything lives in this repo](#appendix-d--where-everything-lives-in-this-repo)

---

# Part 0 — The 30-second version

A messenger is not magic. It is **a socket plus a shared dictionary**:

```
   your client                        Bale servers
   ───────────                        ────────────
   "send 'hi' to peer 42"   ──────►   store it, deliver it
   ◄──────  "peer 7 sent you 'yo'"    push updates
```

The two hard parts are:

1. **The shared dictionary.** Both sides must agree *exactly* how a message is
   turned into bytes. Bale uses **Protocol Buffers** (protobuf). If your bytes
   are wrong by one number, the server ignores the field, or drops the message,
   or closes the socket. There is no error message that says "field 3 was wrong".

2. **The pipe.** The web client opens a **WebSocket** (a long-lived two-way
   connection) and keeps it open. Login happens over plain HTTPS first, because
   you need a token before the socket will accept you.

Everything else — chat lists, avatars, typing dots — is just more dictionary
entries sent down the same pipe.

**The whole project in one sentence:** we extracted Bale's dictionary (2,012
message types found, 2,006 after de-duplication, and 699 remote calls across
53 services) out of their JavaScript, and wrote a program that speaks it.

---

# Part 1 — Concepts from zero

## 1.1 What a chat app actually does

Strip away the design and a messenger does seven things:

| Verb | What it means | Bale calls it |
|---|---|---|
| **Identify** | "I am +98912…, prove it with an SMS code" | `Auth.StartPhoneAuth` → `ValidateCode` |
| **Catch up** | "What conversations do I have, and what is unread?" | `Messaging.LoadDialogs` |
| **Resolve names** | "Who is user 1545969305?" | `Users.LoadUsers`, `Groups.LoadGroups` |
| **Read back** | "Give me the last 20 messages of this chat" | `Messaging.LoadHistory` |
| **Write** | "Send this text / photo / file" | `Messaging.SendMessage` |
| **Stay current** | "Tell me the moment anything changes" | the update stream |
| **Acknowledge** | "I have read up to this message" | `Messaging.MessageRead` |

A client that does all seven is usable. A client that does two of them is a demo.

## 1.2 The five moving parts

Every client, in every language, has these five layers. Learn the layers once and
the language stops mattering.

```
 ┌──────────────────────────────────────────────┐
 │  5. UI           widgets, bubbles, scrolling │  what the user sees
 ├──────────────────────────────────────────────┤
 │  4. App logic    cache, unread counts, names │  YOUR code
 ├──────────────────────────────────────────────┤
 │  3. RPC          service + method + payload  │  "call a function remotely"
 ├──────────────────────────────────────────────┤
 │  2. Envelope     WsRequest / WsResponse      │  Bale's framing
 ├──────────────────────────────────────────────┤
 │  1. Transport    WebSocket / HTTPS           │  bytes over the network
 └──────────────────────────────────────────────┘
```

When something breaks, **always ask "which layer?"** — it turns a mystery into a
five-minute fix. Part 5 is a table of symptoms mapped to layers.

## 1.3 What a protocol really is

A protocol is an agreement about bytes. That is all. When you write

```
 0a 04 08 01 10 2a
```

you are not writing "hello". You are writing seven bytes whose meaning was
decided in advance:

- `0a` → "the next thing is field **1**, and it is length-prefixed"
- `04` → "it is **4** bytes long"
- `08 01` → inside those 4 bytes: field 1, value 1
- `10 2a` → inside those 4 bytes: field 2, value 42

Read that again — **the structure is inside the bytes**. There is no external
schema transmitted. Both sides just know.

## 1.4 Protocol Buffers in ten minutes

Protobuf has exactly **three things** you must understand.

### (a) Varint — how numbers are written

Small numbers take one byte. Bigger numbers take more. Seven bits per byte, and
the top bit means "there is another byte coming":

| Value | Bytes | Why |
|---|---|---|
| 0 | `00` | |
| 1 | `01` | |
| 127 | `7f` | biggest single-byte value |
| 128 | `80 01` | 0 + 1×128 |
| 300 | `ac 02` | 44 + 2×128 |
| -1 | `ff ff ff ff ff ff ff ff ff 01` | ten bytes, two's complement |

That last row matters: **negative numbers are always 10 bytes.** This is why
`minDate = -1` looks huge on the wire and why a client that "sends 0 instead"
gets an empty chat list.

### (b) Tag — what field, and what shape

Every field starts with a tag byte (itself a varint):

```
tag = (field_number << 3) | wire_type
```

| wire_type | Meaning | Used for |
|---|---|---|
| 0 | varint | int32, int64, bool, enum |
| 1 | 64-bit | fixed64, double |
| 2 | length-prefixed | string, bytes, nested message |
| 5 | 32-bit | fixed32, float |

So `0a` = `(1 << 3) | 2` = field 1, length-prefixed. `10` = `(2 << 3) | 0` =
field 2, varint. `1a` = field 3, length-prefixed. You will see `0a`, `10`, `12`,
`18`, `1a`, `22` constantly.

### (c) Nested messages are just bytes

A field of message type is encoded **exactly like a string**: length, then the
inner message's own bytes. No escaping, no braces. That is why decoding is a
loop: read a tag, read a value, repeat.

### Putting it together — a real message

Here is an actual `SendMessage` produced by the codec in this repo
(`0a 04 …` is the peer, `10 …` the random `rid`, and `1a` the message body):

```
0a 04 08 01 10 2a        field 1 (peer), 4 bytes:
                            08 01   peer.type = 1        (1 = user)
                            10 2a   peer.id   = 42
10 95 9a ef 3a           field 2 (rid), varint = 123456789
1a 1e                    field 3 (message), 30 bytes:
   7a 1c                    field 15 (textMessage), 28 bytes:
      0a 1a                   field 1 (text), 26 bytes:
         68 65 6c 6c 6f ...   "hello from a custom client"
```

(43 bytes in total. You can reproduce this with
`python3 -c "from baleclient.proto import default_codec; print(default_codec().encode('Messaging_SendMessage_Request', {...}).hex(' '))"`
— do that whenever you are unsure whether your encoder is right.)

Everything below this line is the same idea repeated 2,000 times with different
field numbers.

### Why field numbers matter more than names

Names (`peer`, `rid`, `text`) are for humans. **The server only sees numbers.**
That is why our schema has a few messages named things like
`Msg_index_fe3fa18088__83329_h` — the minifier deleted the real name, but the
*numbers* survived, and numbers are what work.

## 1.5 WebSockets in ten minutes

Normal HTTP: client asks, server answers, connection closes. Bad for chat — you
would have to ask "anything new?" every second.

A WebSocket starts as an HTTP request with one special header
(`Upgrade: websocket`), and if the server agrees, **the connection stays open and
both sides can send at any time**. After the upgrade, data is sent in *frames*:

```
[1 byte: FIN + opcode][1 byte: mask + length][extended length][4-byte mask][payload]
```

- opcode `2` = binary, `1` = text, `8` = close, `9`/`10` = ping/pong
- **clients must mask** the payload (XOR with 4 random bytes); servers must not
- a client that forgets the mask gets disconnected instantly

Bale's socket lives at `wss://next-ws.bale.ai/ws/` and every frame is **binary**
and contains exactly one protobuf message.

## 1.6 RPC — calling a function that lives on another computer

An RPC is a name plus arguments plus a result:

```
service: bale.messaging.v2.Messaging
method:  SendMessage
request: {peer:…, rid:…, message:…}
result:  {seq:…, date:…}
```

Bale identifies a call by the **pair** (service, method) — both strings are sent
in every request. You can call any of the 699 methods in `out/bale.proto` the
same way; there is no per-method endpoint to configure.

## 1.7 gRPC-web in ten minutes

gRPC is Google's RPC format. Browsers cannot speak raw gRPC, so there is a
slightly-different version called **gRPC-web**, used here only for login:

```
POST https://next-ws.bale.ai/bale.auth.v1.Auth/StartPhoneAuth
content-type: application/grpc-web+proto
body: 00 <4-byte big-endian length> <protobuf bytes>
```

The leading `00` is a "not compressed" flag. The response is the same shape;
strip the first five bytes and you have your protobuf. Errors arrive in a
`grpc-message` header — often with readable text like
`PHONE_CODE_INVALID`, which is a gift when debugging.

## 1.8 Identity: three numbers you must not confuse

| Thing | Looks like | What it is |
|---|---|---|
| **JWT** | `eyJhbGciOi...` (long string) | Your session token. Sent as a cookie on the socket. Expires (~1 year). |
| **access hash** | `-8123456789012345` | A per-contact secret. The server rejects calls that omit it, so you must store it when you first see a peer. |
| **peer id** | `1545969305` | The public numeric id of a user or group. Not secret, but not enough on its own. |

A **peer** is `{type, id, accessHash}` where type `1` = user, `2` = group. Any
time this handbook says "peer", it means that triple.

---

# Part 2 — How Bale actually works

## 2.1 The map

```
                      ┌─────────────────────────┐
  login (once)  ─────►│  HTTPS  gRPC-web        │  bale.auth.v1.Auth
                      │  POST /<svc>/<method>   │  StartPhoneAuth
                      └───────────┬─────────────┘  ValidateCode
                                  │  JWT
                                  ▼
                      ┌─────────────────────────┐
  everything else ───►│  WSS  /ws/              │  699 methods
                      │  Cookie: access_token=  │  + update stream
                      └─────────────────────────┘
```

Two channels because login cannot be authenticated, and the socket requires
authentication. Simple as that.

### Constants (from the bundle — you need these)

| Name | Value | Where it is used |
|---|---|---|
| host | `next-ws.bale.ai` (fallbacks `maviz-ws.bale.ai`, `beta-ws.bale.ai`) | every connection |
| `apiVersion` | `176652` | handshake, metadata |
| `mkprotoVersion` | `1` | handshake |
| `appId` | `4` | login (4 = web) |
| `apiKey` | `C28D46DC…DE4E7D` | login |
| `Origin` | `https://web.bale.ai` | every HTTP/WS request |
| metadata | `app_version`, `browser_type`, `os_type`, `browser_version` | every request; the official client also sends `mt_`-prefixed copies |
| ping / timeout | every **10 s**, 5 s timeout | keep-alive |
| reconnect | 100 ms → ×2 → max 3 s | after a drop |
| close code `4401` | "not authenticated" | do **not** reconnect, re-login instead |

Two honest deviations in this repo's client, both harmless: it pings every
**20 s** instead of 10 s (half the traffic, same effect), and it starts the
reconnect backoff at **200 ms** instead of 100 ms. Copy the official numbers if
you want to be exact; the server does not care about either.

## 2.2 The envelope (this is the part everyone gets wrong)

Inside the WebSocket, every frame is a `WsRequest`:

```protobuf
message WsRequest {
  Request          request          = 1;   // an RPC call
  Ping             ping             = 2;   // keep-alive
  HandshakeRequest handshakeRequest = 3;   // first frame
}

message Request {
  string   serviceName = 1;   // "bale.messaging.v2.Messaging"
  string   method      = 2;   // "LoadDialogs"
  bytes    payload     = 3;   // the encoded request message
  Metadata metadata    = 4;   // key/value map (see below)
  int64    index       = 5;   // YOUR correlation id — pick any unique number
}

message Metadata {
  repeated Entry entry = 1;                 // Entry { 1: string key, 2: {1: string value} }
}

message Ping             { int64 id = 1; }                                 // field 1 = "ID"
message HandshakeRequest { int32 mkprotoVersion = 1; int64 apiVersion = 2; }
```

And every answer is a `WsResponse`:

```protobuf
message WsResponse {
  Response   response         = 1;
  Update     update           = 2;   // something changed — handle this too!
  TerminateSession terminateSession = 3;
  Pong       pong             = 4;
  HandshakeResponse handshakeResponse = 5;
}

message Response { Error error = 1; bytes response = 2; int64 index = 3; }
message Error    { int32 topic = 1; string message = 2; }
message Pong     { int64 id = 1; }
message HandshakeResponse { int32 mkprotoVersion = 1; int64 apiVersion = 2;
                            int64 serverTime = 3; }
```

**The rule that trips everyone up:** a frame is *either* a response *or* an
update — and updates can arrive while you are waiting for a response. If your
read loop assumes "next frame is my answer", it will hang the first time the
other person types. Always:

```
read frame → if update: handle it, keep waiting
           → if response.index == my index: done
           → otherwise: ignore (stale or foreign)
```

## 2.3 The lifecycle, in order

```
1. CONNECT      wss://next-ws.bale.ai/ws/        (Cookie: access_token=<jwt>)
2. HANDSHAKE    → {3: {1:1, 2:176652}}
                ← {5: {1:1, 2:176652, 3:<server time>}}     must arrive < 10 s
3. PING         every 10 s: → {2:{1:<id>}} ;  ← {4:{1:<id>}}
4. CALL         → {1:{1:svc, 2:method, 3:payload, 4:meta, 5:index}}
                ← {1:{2:result, 3:index}}      or {1:{1:{1:topic,2:msg}, 3:index}}
5. UPDATES      ← {2:{1:<Update bytes>}}        at any time
6. CLOSE        4401 = bad token → stop and re-login
                anything else → reconnect with backoff, redo handshake
```

### The login flow (before step 1)

```
POST /bale.auth.v1.Auth/StartPhoneAuth
  { 1: phoneNumber   (int64 — full international number, digits only:
                       "989121234567" not "+98 912 123 4567", no leading 0),
    2: appId = 4,
    3: apiKey,
    4: deviceHash    (bytes — keep it stable per device),
    5: deviceTitle   ("Chrome_135.0.0.0, Windows"),
    9: sendCodeType = 1,      // 1 = SMS
    10: options = [] }
→ { 1: transactionHash, 2: isRegistered, 3: activationType,
    5: sentCodeType, 6: codeExpirationDate, … }

POST /bale.auth.v1.Auth/ValidateCode
  { 1: transactionHash, 2: code, 3: {1: true} }   // 3 = isJwt
→ { 2: user {1: id, 2: accessHash, 3: name},
    3: config,                                    // server settings
    4: jwt }                                      // yes: field FOUR, not one

// only if activationType says a password is needed:
POST /bale.auth.v1.Auth/ValidatePassword
  { 1: transactionHash, 2: password, 3: {1: true} }
// only for a brand-new number:
POST /bale.auth.v1.Auth/SignUp
  { 1: transactionHash, 2: name, 3: sex, 4: password }
```

Then store the JWT and open the socket. The JWT here is valid for about a year;
check the `exp` claim rather than assuming.

## 2.4 The message shapes you will actually use

Below, `→` is the request field list and `←` is the response. Field numbers are exact.

### Dialogs (the chat list)

```
→ Messaging.LoadDialogs {
    1: minDate = -1,          // -1 = first page. 0 = EMPTY LIST. (the classic bug)
    2: limit = 50,
    3: optimizations = b"",   // incremental-sync blob, empty on a cold start
    4: dialogType = 0,        // 0 = all folders
    5: excludePinnedDialogs = false,
    6: archiveFilter = 0 }    // 0 = all
← Messaging_LoadDialogs_Response {
    1: groups, 2: users,                  // often already populated
    3: repeated Dialogs, 4: userPeers, 5: groupPeers }

Dialogs { 1: peer, 2: unreadCount, 3: sortDate, 4: senderUid, 5: rid,
          6: date, 7: message (Message2), 8: state, 9: firstUnreadDate }
```

**Dialogs carry no names.** That is the second classic bug: your chat list shows
raw numbers. Resolve them:

```
→ Users.LoadUsers  { 1: repeated {1: uid, 2: accessHash} }  ← { 1: users }
→ Groups.LoadGroups{ 1: repeated {1: gid, 2: accessHash} }  ← { 1: groups }
Users  { 1: id, 2: accessHash, 3: name, 6: avatar, … }
Groups2{ 1: id, 2: accessHash, 3: title, 4: avatar, … }
```

### History

```
→ Messaging.LoadHistory {
    1: peer,
    2: date = -1,         // -1 = newest page; otherwise "older than this"
    4: loadMode = 2,      // 1 forward, 2 backward, 3 both
    5: limit = 20,
    6: optimizations = b"" }
← { 1: repeated History, 2: hasMore? , … }

History { 1: senderUid, 2: rid, 3: date, 4: Message2 message, 5: state,
          6: reactions, 7: attributes, 8: quotedMessage, 9: seq,
          10: previousMessageId, 12: editedAt }

Message2 { 1: bankMessage, 2: binaryMessage, 3: deletedMessage,
           4: documentMessage, 7: jsonMessage, 11: serviceMessage,
           12: stickerMessage, 13: templateMessage,
           15: textMessage, … }        // textMessage { 1: string text }
```

`Message2` is a **union**: exactly one field is set. Your text check is
"is field 15 present?" — nothing more. If it is not, fall back to a friendly
label like `[photo]`.

Two exceptions worth knowing on day one: **long** messages come back as
`longTextMessage` (**field 30**, text at `{1: text}`) instead of 15, and service
events (joins, title changes) come back as `serviceMessage` (field 11) with a
ready-made sentence in `{1: text}`. Treat 15, 30 and 11 as text, and you will
never see a blank bubble again.

### Send

```
→ Messaging.SendMessage {
    1: peer,
    2: rid,                                  // random int64, unique per chat
    3: { 15: { 1: "your text" } },           // Message2 → textMessage → text
    5: { … } }                               // optional quote: {1: peer, 2: rid, 3: date, 4: seq}
← { 1: seq, 2: date, 3: state, 5: seqBig }     // where the server placed it
```

Note what is **not** in that response: the message itself. You only get its
`date` and `seq`; the content is yours to render, and the `messageSent` update
(field 4) confirms delivery by echoing your `rid`.


You do **not** get your message back. Render it optimistically, then reconcile
with the `messageSent` update (which carries your `rid`).

### Read receipts

```
→ Messaging.MessageRead { 1: peer, 2: date }
← (empty)
← update: { 19: messageRead { 1: peer, 2: startDate, 3: readDate } }
```

### Typing

```
→ bale.presence.v1.Presence/Typing     { 1: peer, 3: typingType }
→ bale.presence.v1.Presence/StopTyping { 1: peer, 2: typingType }
      typingType: 1 text, 2 voice, 3 photo, 4 video

Note the deliberate trap: `Typing` puts the type in field **3**, `StopTyping`
puts it in field **2**. Send `StopTyping` with field 3 and the other side's
typing indicator never clears.
← update: {6: typing {…}}, {…: typingStop {…}}
```

Send `Typing` at most every ~4 s while the user types. Do not send it on every
keypress; you will flood the socket.

## 2.5 The update stream

Updates are how the server pushes change to you. The union has ~147 variants; you
need very few to feel complete:

| Variant | Field | Meaning |
|---|---|---|
| `messageReceived` | **54** | someone sent you a message |
| `messageSent` | 4 | your own message was accepted (match by `rid`) |
| `message` | 55 | message payload variant (Message3) |
| `typing` / `typingStop` | 6 / **81** | the other side is typing |
| `messageRead` | 19 | they read up to `readDate` |
| `messageReadByMe` | 50 | another device of yours read a chat |
| `messageDelete` | 46 | a message vanished |
| `userOnline` / `userOffline` / `userLastSeen` | 7 / 8 / 9 | presence |
| `userNameChanged` / `userAvatarChanged` | 32 / 16 | profile refresh |
| `reactionsUpdate` | 222 | reaction added/removed |
| `chatGroupsChanged`, `contactRegistered` | 1, 5 | list changes |
| `groupTitleChanged`, `groupAvatarChanged` | 2609, 2610 | group refresh |
| `groupMembersUpdated`, `groupMemberChanged` | 2614, 2612 | member refresh |

An update you do not recognise is **not an error**. Decode what you know, ignore
the rest, and keep the socket open. A client that crashes on unknown updates will
crash within a minute of real use.

---

# Part 3 — Write your own client

## 3.1 What you need

**Accounts and access**

- [ ] A phone number that can receive Bale's SMS (Iranian number in practice)
- [ ] Network access to `next-ws.bale.ai` (port 443). If you are outside Iran and
      TLS hangs, route through an Iranian proxy — `BALE_PROXY=http://host:port`
      in this project.
- [ ] The right to test: use **your own** account. Do not touch anyone else's.

**Code**

- [ ] The schema: `baleclient/schema.json` (raw) or `out/bale.proto` (protoc-ready)
- [ ] A WebSocket client for your language
- [ ] A protobuf encoder — generated code, or 40 lines you write yourself
- [ ] An HTTP client for the login call

**A language from the allowed list.** The challenge accepts **Java, C#, Kotlin,
Python**. (Go/Rust/JS are fine for your own learning, but do not submit them.)

**Time budget for a first working client:** an evening for login + chat list, a
weekend for a usable GUI.

## 3.2 The build plan

Do it in this order. Each step is testable on its own, which is the whole point.

| Step | You get | Done when |
|---|---|---|
| 1 | encode/decode | a handshake frame equals `1a 06 08 01 10 8c e4 0a` |
| 2 | socket | you can open `/ws/` and read the handshake answer |
| 3 | RPC | one call returns bytes without raising |
| 4 | login | you hold a JWT |
| 5 | chat list | 20 titles appear, not 20 numbers |
| 6 | history | messages in the right order |
| 7 | send | your phone buzzes |
| 8 | updates | the open chat updates live |
| 9 | files | a photo uploads and renders |
| 10 | resilience | kill Wi-Fi for 10 s, it recovers |

## 3.3 Step 1 — encoding, verified against a known answer

This is the single most useful trick in the whole handbook: **compare bytes
against a known-good frame.**

The handshake must serialize to exactly:

```
1a 06 08 01 10 8c e4 0a
```

Read it: `1a` = field 3, length-prefixed, 6 bytes → `08 01` (field 1 = 1, the
protocol version), `10 8c e4 0a` (field 2 = 176652, the app version). If your
encoder produces those eight bytes, your varint and tag code is correct and
everything else will work.

## 3.4 Step 2 — the socket

Three things to get right:

1. **Headers.** `Cookie: access_token=<jwt>` and `Origin: https://web.bale.ai`.
   Some HTTP libraries let you override cookies; if not, set the header manually.
2. **Binary frames only.** Never send text.
3. **Mask your frames.** Every client library does this for you; a hand-rolled
   socket must do it itself.

If the socket connects and then closes immediately, it is almost always a bad or
expired token (close code `4401`) or a missing `Origin`.

## 3.5 Step 3 — the RPC call

The minimum viable implementation:

```
index = next_number()
send( Envelope.request(service, method, payload, index, metadata) )
loop:
    frame = parse(read())
    if frame is Update:  queue it; continue
    if frame is Response and frame.index == index:
        if frame.error: raise RpcError(frame.error.message)
        return frame.result
```

Two details that save hours: **persist your `index` counter across reconnects**
(duplicate indexes confuse correlation if you mix old and new sockets), and
**always time out** (20 s is generous; a missing timeout turns a dropped frame
into a frozen app).

## 3.6 Step 4 — login

Do the four gRPC-web calls from §2.3. Store:

```
{ "token": "<jwt>", "me": {"id":…, "name":…, "accessHash":…},
  "deviceHash": "<16 bytes, hex>", "host": "next-ws.bale.ai" }
```

in a file with `0600` permissions. On next start, skip login entirely and go
straight to the socket. The `deviceHash` must stay the same, or Bale may treat
the login as a new device.

**Never send this file anywhere.** Not to the challenge organisers, not in a ZIP,
not in a screenshot.

## 3.7 Step 5 — read: dialogs → users → history

The correct order for a cold start:

```
1. LoadDialogs(minDate=-1)          → ids, unread counts, last message
2. collect peer ids from the answer
3. LoadUsers(ids) + LoadGroups(ids) → titles and avatars
4. render the list
5. on click: LoadHistory(peer, date=-1, loadMode=2)
```

If you skip step 3 you get numbers. If you use `minDate=0` you get nothing.
Those two bugs account for most "my client doesn't work" messages.

## 3.8 Step 6 — write: sending

Generate a `rid` (any random int64), build `Message2.textMessage`, send, render
immediately, and mark it pending until `messageSent` or an error arrives. If the
call throws, show a retry button instead of dropping the message silently.

## 3.9 Step 7 — files (the part that looks scary and isn't)

Three calls, in order:

```
1. Files.GetNasimFileUploadUrl {
     expectedSize: <byte count>, crc: 0 (or omit), uid: <your user id>,
     name: <file name>, mimeType: <mime>,
     chunkSize: 0, compressedSize: 0, fileKind: 0,   // what the web client sends
     exPeer: { type, id, accessHash },               // where it will be sent
     sendType: { type: 1 },                          // 1 photo   2 video   3 voice
   }                                                 // 4 gif     5 audio   6 document
                                                     // 7 sticker
   → { fileId, url, chunkSize, … }

2. PUT <url>   Content-Type: multipart/form-data   body: raw bytes
   (chunked only when the server returns chunkSize > 0: PUT each <chunkSize>
    slice with headers `chunk: <index>` and `no: <total>`, then finish with an
    empty PUT carrying `chunk: <total>`)

3. Messaging.SendMessage {
     peer, rid,
     message: { 4: documentMessage {
         fileId,              // from step 1 — that is all the server needs
         fileSize, name, mimeType,
         ext: { documentExPhoto: {w: 0, h: 0} },   // photos only
         caption: { text } } } }                   // optional
```

Note what is **not** there: `accessHash`. A freshly uploaded `fileId` is enough
for `SendMessage`; the hash comes back from the server when the message is
read. If you already know the hash (forwarding an existing file) you may send
it, but never invent one.

Downloading is the mirror image: `Files.GetNasimFileUrl { file: {fileId,
accessHash}, ciphertext: false }` → `{fileUrl: {url}}`, then a plain HTTPS GET.

Avatars use the same download path; the pair comes from
`Users.avatar.smallImage.fileLocation` (`{fileId, accessHash}`).

## 3.10 Step 8 — stay current

Handle the six updates from §2.5, and re-render only what changed. If you redraw
the whole list on every update, your app will feel slow at 20 messages/minute.

## 3.11 Step 9 — survive

- reconnect with exponential backoff (100 ms → 3 s), redo the handshake
- do **not** reconnect on `4401` — re-login instead
- check `exp` in the JWT once a day; refresh before it dies
- every call needs a timeout; every UI action needs a failure state

## 3.12 Step 10 — cookbook per language

The pieces are the same; only the library names change.

### Python (this project)

The one to copy: it is short, it is the real thing, and it has been run end to
end against the mock server in Part 6.

```python
import asyncio
import websockets
from baleclient.proto import default_codec
from baleclient.transport import (encode_handshake, encode_ping,
                                  encode_request_body, decode_frame)

codec = default_codec()


def handle_update(update):
    print("update:", list(update)[:3])       # replace with real handling


async def call(ws, index, service, method, payload):
    """Send one RPC and wait for ITS answer - skipping updates on the way."""
    await ws.send(encode_request_body(service, method, payload, index))
    while True:
        frame = decode_frame(codec, await ws.recv())
        if "update" in frame:                     # not ours: handle and keep waiting
            handle_update(frame["update"])
            continue
        resp = frame.get("response") or {}
        if resp.get("number") == index:           # field 3: the index echoed back
            if resp.get("error"):
                raise RuntimeError(resp["error"]["message"])
            return resp.get("result", b"")


async def main(jwt):
    async with websockets.connect(
            "wss://next-ws.bale.ai/ws/",
            additional_headers={"Cookie": f"access_token={jwt}",
                                "Origin": "https://web.bale.ai"}) as ws:
        await ws.send(encode_handshake())
        print("handshake:", decode_frame(codec, await ws.recv()))

        payload = codec.encode("Messaging_LoadDialogs_Request",
                               {"minDate": -1, "limit": 50, "optimizations": [],
                                "dialogType": 0, "archiveFilter": 0})
        raw = await call(ws, 1, "bale.messaging.v2.Messaging",
                         "LoadDialogs", payload)
        dialogs = codec.decode("Messaging_LoadDialogs_Response", raw)
        print(len(dialogs.get("dialogs", [])), "dialogs")


# asyncio.run(main(token))
```

Run against the mock it prints:

```
handshake: {'handshake': {'mkprotoVersion': 1, 'apiVersion': 176652, 'serverTime': …}}
2 dialogs
```

Notes that matter: `additional_headers` is the `websockets>=13` name (older
versions call it `extra_headers`), and if you point this at `ws://` instead of
`wss://` the mock server works without TLS.

### Java

*The Java, Kotlin and C# snippets below are sketches of the same loop;
the Python one above is the executable reference.*

```java
// --- socket (OkHttp) ---
OkHttpClient http = new OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS).build();

Request req = new Request.Builder()
        .url("wss://next-ws.bale.ai/ws/")
        .header("Cookie", "access_token=" + jwt)
        .header("Origin", "https://web.bale.ai")
        .build();

WebSocket ws = http.newWebSocket(req, new WebSocketListener() {
    @Override public void onMessage(WebSocket s, ByteString bytes) {
        WsResponse frame = WsResponse.parseFrom(bytes);      // generated by protoc
        if (frame.hasUpdate())     handleUpdate(frame.getUpdate());
        if (frame.hasResponse())   complete(frame.getResponse());
    }
});
ws.send(ByteString.copyFrom(requestFrame));   // WsRequest.toByteArray()

// --- login (gRPC-web over plain HTTP) ---
byte[] payload = StartPhoneAuthRequest.newBuilder()
        .setPhoneNumber(989121234567L).setAppId(4).setApiKey(API_KEY)
        .setDeviceHash(ByteString.copyFrom(hash))
        .setDeviceTitle("Chrome_135.0.0.0, Windows").setSendCodeType(1).build()
        .toByteArray();
byte[] body = ByteBuffer.allocate(5 + payload.length)
        .put((byte) 0).putInt(payload.length).put(payload).array();
```

Generate the classes once:

```bash
protoc --java_out=src/main/java --proto_path=. out/bale.proto
# or, for smaller code on Android / desktop:
protoc --java_lite_out=src/main/java --proto_path=. out/bale.proto
```

Libraries: `okhttp` (socket), `protobuf-java` or `protobuf-javalite`,
`java.net.http.HttpClient` (login) or OkHttp for both.

### Kotlin

```kotlin
val client = OkHttpClient.Builder()
    .pingInterval(10, TimeUnit.SECONDS)
    .build()

val request = Request.Builder()
    .url("wss://next-ws.bale.ai/ws/")
    .header("Cookie", "access_token=$jwt")
    .header("Origin", "https://web.bale.ai")
    .build()

val ws = client.newWebSocket(request, object : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val frame = WsResponse.parseFrom(bytes)
        if (frame.hasUpdate()) handleUpdate(frame.update)
        if (frame.hasResponse()) complete(frame.response)
    }
})
```

Generate with protoc (`--kotlin_out` exists in newer protoc) or — nicer — use
**Square Wire**, which compiles `.proto` files into idiomatic Kotlin data classes:

```kotlin
// build.gradle.kts
plugins { id("com.squareup.wire") }
wire { kotlin { srcs("out/bale.proto") } }
```

Libraries: OkHttp (or Ktor Client on Android), Wire or protobuf-kotlin.

### C#

```csharp
using System.Net.WebSockets;
using Google.Protobuf;

var ws = new ClientWebSocket();
ws.Options.SetRequestHeader("Cookie", $"access_token={jwt}");
ws.Options.SetRequestHeader("Origin", "https://web.bale.ai");
await ws.ConnectAsync(new Uri("wss://next-ws.bale.ai/ws/"), ct);

await ws.SendAsync(requestFrame, WebSocketMessageType.Binary, true, ct);

var buffer = new byte[64 * 1024];
var result = await ws.ReceiveAsync(buffer, ct);
var frame  = WsResponse.Parser.ParseFrom(buffer, 0, result.Count);
if (frame.Update  != null) HandleUpdate(frame.Update);
if (frame.Response != null) Complete(frame.Response);
```

```bash
protoc --csharp_out=Gen --proto_path=. out/bale.proto
```

Libraries: `System.Net.WebSockets.ClientWebSocket` (built in),
`Google.Protobuf` (NuGet), `HttpClient` for the login POST (the 5-byte
gRPC-web header is just a byte array — see §1.7).

> **Note for all four:** if you would rather not depend on protoc, copy the
> ~40 lines of `varint`/`put_int`/`put_bytes` from
> `docs/examples/minimal_client.py` into your language. Hand-rolling the
> encoding is perfectly legitimate and, for a client this size, often less work
> than wiring a build step.

## 3.13 Library matrix

| Need | Python | Java | Kotlin | C# |
|---|---|---|---|---|
| WebSocket | `websockets` | OkHttp | OkHttp / Ktor | built-in `ClientWebSocket` |
| HTTP | `urllib` / `httpx` | OkHttp / `HttpClient` | OkHttp / Ktor | `HttpClient` |
| protobuf | `protobuf` or this codec | `protobuf-java(lite)` | Wire / `protobuf-kotlin` | `Google.Protobuf` |
| JSON (for the session) | `json` | Jackson / Gson | kotlinx.serialization | `System.Text.Json` |
| GUI | tkinter (stdlib) | Swing / JavaFX | Compose / Android | WPF / WinForms / MAUI |
| Zero-dependency option | **yes** (see `minimal_client.py`) | hard | hard | mostly |

## 3.14 Beginner mistakes, and the symptom each produces

| Mistake | What you see |
|---|---|
| `minDate: 0` in LoadDialogs | a valid but empty chat list |
| `date: 0, loadMode: 0` in LoadHistory | an empty conversation |
| skipping `LoadUsers`/`LoadGroups` | titles are numbers |
| forgetting `accessHash` | "PEER_NOT_FOUND"-style errors |
| reading the next frame as "the answer" | the app hangs when someone types |
| no timeout on calls | frozen UI with no error |
| reconnecting after `4401` | a reconnect loop that never succeeds |
| sending `sendType` for documents | uploads fail or arrive as photos |
| text frames instead of binary | the server ignores you |
| decoding unknown updates as fatal | crashes within a minute |
| handling only field 15 as text | long messages render as empty bubbles |

---

# Part 4 — Making it feel like a real messenger

## 4.1 The feature checklist judges actually notice

- [ ] login with SMS code, plus 2FA password and sign-up paths
- [ ] chat list with **real names**, avatars, unread badges, last message, time
- [ ] message view with day separators, sender names in groups, grouping
- [ ] optimistic send, ticks, and a visible failure state
- [ ] **live updates** without reopening the chat
- [ ] attachments: photos render inline, files download
- [ ] reply, edit, delete, forward
- [ ] typing indicator
- [ ] search
- [ ] dark mode, RTL/LTR, a window that survives resizing
- [ ] session persistence — no SMS on every launch

## 4.2 Persian text, RTL, and why your GUI looks broken

Persian is right-to-left and uses **contextual glyphs**: a letter looks different
alone, at the start, in the middle, and at the end of a word. Browsers do this
automatically; most native toolkits do not.

- **Web UI:** set `dir="rtl"` and you are done.
- **Tk / Swing / raw WPF:** you must shape and reorder yourself.
  `baleclient/rtl.py` does it in 250 lines: derive the Unicode presentation
  forms from `unicodedata` names, apply joining rules and the `لا` ligature,
  then reorder runs so Latin words and numbers stay readable inside a Persian
  sentence. Send the original text on the wire; only the widget sees the
  reordered string.
- **Android / Compose / Flutter:** built-in bidi, but verify alignment.

A reversed Persian sentence is the fastest way to lose credibility in front of a
Persian-speaking judge. Test it early.

## 4.3 Things that make it feel fast

- cache users/groups; never fetch a name twice
- cache decoded avatars and images in memory, keyed by `fileId`
- render history incrementally (20 → 40 → 60), not all at once
- keep one socket for the whole app; never a socket per conversation
- debounce typing notifications (4 s), search (300 ms), and resize

---

# Part 5 — Debugging playbook

## 5.1 Ask "which layer?" first

| Symptom | Likely layer | First check |
|---|---|---|
| cannot connect at all | transport | DNS/TLS, proxy, `Origin` header |
| connects then instantly closes | transport / auth | close code, token validity (`4401`) |
| handshake never answered | envelope | your frame is malformed — compare to `1a 06 08 01 10 8c e4 0a` |
| every call times out | envelope | are you reading frames at all? is `index` echoed? |
| server returns an error string | application | read it — Bale's messages are literal |
| empty dialogs | application | `minDate` must be `-1` |
| empty history | application | `date:-1, loadMode:2` |
| names missing | application | `LoadUsers` + `LoadGroups` |
| messages show but avatars don't | files | `fileId`/`accessHash`, then `GetNasimFileUrl` |
| works, then dies after ~60 s | keep-alive | you are not sending pings |

## 5.2 Techniques that pay for themselves

1. **Hex-dump everything.** Log request and response bytes for the first 100
   calls. `xxd` and a printed length catch malformed frames instantly.
2. **Annotate one frame by hand.** Decode a single real message field by field
   (like §1.4). Once you have done it manually, the wire stops being magic.
3. **A `probe` command.** This project has one (`python run.py probe`) that
   prints the session path, token fingerprint, JWT expiry, `GetFullUser`, and the
   raw `LoadDialogs` bytes. Build the equivalent early.
4. **Compare against the web client.** Open Bale in a browser with developer
   tools → Network → WS, and watch the frames it sends. That is the ground truth.
5. **Reproduce offline.** A mock server (Part 6) turns "it's flaky" into a
   deterministic test.

## 5.3 Reading a Bale error

Errors come as `{topic: <int>, message: "<text>"}`. The text is usually
self-explanatory (`PHONE_CODE_INVALID`, `password needed for login`,
`PEER_NOT_FOUND`). Print both parts — the topic sometimes tells you which
subsystem rejected you.

---

# Part 6 — Testing without an account

You do not need a phone number to test the protocol. You need a **fake server**.

`docs/examples/test_minimal_client.py` runs a local WebSocket that speaks the
real protocol — handshake, indexed responses, update pushes, pings — and asserts
that the example client behaves. It is ~140 lines and runs in under a second:

```bash
pip install websockets
python docs/examples/test_minimal_client.py
# 1) connect + handshake
# 2) LoadDialogs -> dialogs for peers: [111, 222]
# 3) SendMessage
# 4) server saw: ['LoadDialogs', 'SendMessage']
# 5) ping frame -> pong 7
# all protocol checks passed against a local server
```

The same script is wired into the test suite (`tests/test_example_client.py`), so
`python -m unittest discover -s tests` exercises the wire format on every run.

**What this buys you:** you can develop the entire client — including the GUI —
with no account, no SMS, and no network. Use it for screenshots and demos too;
`python run.py native --demo` and `python run.py gui --demo` do exactly that.

---

# Part 7 — Rules, ethics, safety

This matters more than the code.

**The challenge's rules.** Source plus a build, in Java, C#, Kotlin or Python,
mailed to the address in the repository. That is the whole ask — do not invent
extra requirements.

**On the prize.** A repository created the day the challenge appeared, with an
email-only contact and a large unverifiable sum, deserves caution. Take dated
records, never pay a fee to "release" a prize, and never send credentials. If it
is real, your work stands on its own merits; if it is not, you lost only time.

**What not to do, ever:**

- do not log into accounts that are not yours
- do not ship a session file, phone number, or SMS code in the ZIP
- do not build spam, scraping or bulk-message features
- do not use this client to impersonate another person or Bale support
- do not hammer the servers; one account, normal usage, back off on errors

**A clean submission contains:** source, a build/run instruction, the schema and
`.proto`, the write-up, and screenshots — and no secrets. Check with:

```bash
grep -ri "access_token\|eyJ" . | grep -v "\.md\|\.pyc"
```

The string should appear only in source code and documentation, never in data
files.

---

# Appendix A — Field cheat sheet

Everything you need for a usable client, in one page.

```
CONSTANTS
  host          next-ws.bale.ai
  apiVersion    176652        mkprotoVersion 1
  appId         4             origin https://web.bale.ai
  ping          10 s          reconnect 100 ms → 3 s, cap 3 s
  close 4401    unauthenticated

ENVELOPE
  WsRequest  {1 Request, 2 Ping{1 id}, 3 Handshake{1 mkproto, 2 apiVersion}}
  Request    {1 service, 2 method, 3 payload, 4 metadata, 5 index}
  Metadata   {1 repeated {1 key, 2 {1 value}}}
  WsResponse {1 Response, 2 Update{1 bytes}, 3 Terminate, 4 Pong{1 id}, 5 Handshake}
  Response   {1 Error{1 topic, 2 message}, 2 result, 3 index}

AUTH (gRPC-web)
  StartPhoneAuth    {1 phone, 2 appId=4, 3 apiKey, 4 deviceHash, 5 deviceTitle,
                     9 sendCodeType=1, 10 options}
                                          → {1 transactionHash, 2 isRegistered,
                                             3 activationType, 5 sentCodeType}
  ValidateCode      {1 tx, 2 code, 3 {1 true}} → {2 user, 3 config, 4 jwt}
  ValidatePassword  {1 tx, 2 password, 3 {1 true}}
  SignUp            {1 tx, 2 name, 3 sex, 4 password}

READ
  LoadDialogs  {1 minDate=-1, 2 limit, 3 optimizations, 4 dialogType=0,
                5 excludePinned, 6 archiveFilter=0}
  LoadHistory  {1 peer, 2 date=-1, 4 loadMode=2, 5 limit, 6 optimizations}
  Users.LoadUsers   {1 repeated {1 uid, 2 accessHash}}
  Groups.LoadGroups {1 repeated {1 gid, 2 accessHash}}
  Groups.LoadMembers{1 group, 2 limit}

WRITE
  SendMessage      {1 peer, 2 rid, 3 {15 {1 text}}, 5 quote}
  MessageRead      {1 peer, 2 date}
  DeleteMessage    {1 peer, 3 dates{1 repeated int64}, 4 {1 justMine}}
  UpdateMessage    {1 peer, 2 rid, 3 updatedMessage}
  ForwardMessages  {1 peer, 3 repeated {1 peer, 2 rid, 3 date, 4 seq}}
                                              // QuotedMessageReference
  Presence.Typing  {1 peer, 3 typingType}   StopTyping {1 peer, 2 typingType}

FILES
  GetNasimFileUploadUrl {1 expectedSize, 2 crc="", 3 uid, 4 name, 5 mimeType,
                         6 exPeer, 7 sendType, 8 chunkSize, 9 compressedSize,
                         10 fileKind}       → {1 fileId, 2 url, 4 chunkSize}
  GetNasimFileUrl       {1 {1 fileId, 2 accessHash}, 2 ciphertext=false}
                                          → {1 {1 fileId, 2 url}}
  PUT url   Content-Type: multipart/form-data     (+ chunk/no headers if chunked)

MESSAGES
  History  {1 senderUid, 2 rid, 3 date, 4 Message2, 6 reactions, 8 quoted, 9 seq}
  Message2 {4 document, 11 service, 12 sticker, 13 template, 15 text,
            30 longText}          // 15/30/11 all carry {1: text}
  TextMessage {1 text}       DocumentMessage {1 fileId, 2 accessHash, 3 size,
                             4 name, 5 mime, 7 ext, 8 caption}
```

# Appendix B — Service map

The services you will actually touch (of the 53 extracted):

| Service | Used for |
|---|---|
| `bale.auth.v1.Auth` | login, 2FA, sign-up, logout, sessions |
| `bale.messaging.v2.Messaging` | dialogs, history, send, read, delete, forward, pins |
| `bale.users.v1.Users` | profiles, contact search, `LoadUsers` |
| `bale.groups.v1.Groups` | groups, members, admins, invites |
| `ai.bale.server.Files` | upload/download URLs |
| `bale.abacus.v1.Abacus` | reactions |
| `bale.search.v1.Search` | message/peer/media search |
| `bale.presence.v1.Presence` | typing, online status |
| `bale.v1.Configs` | server configuration |

Full list: `out/schema.json` → `services`, or `docs/services.md`.

# Appendix C — Glossary

| Term | Plain-English meaning |
|---|---|
| **protobuf** | A way to turn structured data into bytes, agreed in advance by both sides |
| **varint** | A number written in 7-bit chunks, so small numbers stay small |
| **tag** | The first bytes of a field: which field, and what shape |
| **wire type** | 0 number, 1 64-bit, 2 length-prefixed, 5 32-bit |
| **message / union** | A struct; a union has one field set out of many |
| **RPC** | Calling a function that runs on the server |
| **service / method** | The two-name address of one RPC |
| **envelope** | The wrapper that carries an RPC (`WsRequest`/`WsResponse`) |
| **index** | Your correlation id; the server echoes it back |
| **frame** | One WebSocket packet |
| **masking** | XOR-ing client frames with a 4-byte key (required by RFC 6455) |
| **handshake** | The first exchange that agrees versions |
| **update** | An unsolicited push from the server |
| **peer** | `{type, id, accessHash}` — a user (1) or group (2) |
| **access hash** | A per-peer secret required by most calls |
| **JWT** | Your session token, sent as a cookie |
| **gRPC-web** | gRPC for browsers: HTTP POST + a 5-byte frame header |
| **optimizations** | Bale's incremental-sync blob; empty on a cold start |
| **rid** | Random per-message id used to match your send with its echo |

# Appendix D — Where everything lives in this repo

```
bale-client/
├── HANDBOOK.md                 ← you are here
├── README.md                   what the project is, how to run it
├── SUBMISSION.md               what to send, and the cover note
├── PROTOCOL.md                 the reverse-engineering write-up (the "why")
├── run.py                      entry point
├── baleclient/
│   ├── proto.py                schema-driven protobuf codec (~340 lines)
│   ├── schema.json             2,006 messages / 699 RPCs / 53 services
│   ├── transport.py            WebSocket + gRPC-web + handshake + ping
│   ├── client.py               login, dialogs, history, send, media, search
│   ├── cli.py                  login / chats / history / send / listen / repl
│   ├── webui.py                browser GUI backend + shared app logic
│   ├── tkgui.py                native desktop GUI (pure Python, no browser)
│   ├── rtl.py                  Persian shaping + bidi for Tk
│   └── static/index.html       browser GUI front end
├── docs/
│   ├── examples/minimal_client.py      ~500-line no-dependency client ← read this
│   ├── examples/test_minimal_client.py its mock server + assertions
│   ├── services.md             all 53 services
│   └── screenshots/            what it looks like
├── out/
│   ├── bale.proto              ~12,600-line protoc-ready schema
│   └── schema.json             raw extraction
├── tests/                      32 tests, all offline
└── tools/                      extractor + schema builder (reproducible)
```

**Suggested reading order for a newcomer:**

1. `README.md` — what the project is, and how to run it.
2. Part 1 of this handbook — the concepts, with no jargon.
3. `docs/examples/minimal_client.py` — ~500 lines and it is the whole protocol.
4. `PROTOCOL.md` — the deeper reverse-engineering details.
5. `baleclient/client.py` — the full application (login, media, search, reactions).
