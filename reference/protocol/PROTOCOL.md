# The Bale wire protocol

Everything in this document was recovered **from the production web client
shipped in `bale.rar`** (`web@5.8.0+176652`). No server was brute forced, no
private API keys were guessed: the client contains the full protobuf schema
and the transport logic, and this is a description of what it does.

> **New to this?** Read [`HANDBOOK.md`](HANDBOOK.md) first — it explains protobuf,
> WebSockets and the envelope from zero, and then walks through building a client
> in Python, Java, Kotlin or C#. This file is the reference; that one is the tour.

The machine readable result lives in [`out/schema.json`](../out/schema.json),
the generated definitions in [`out/bale.proto`](../out/bale.proto), and the
extraction tool in [`tools/extract_protos.py`](../tools/extract_protos.py).

---

## 1. How the schema was extracted

The bundle is built with rspack and contains **protoc generated JavaScript**
(the classic `google-protobuf` output, compacted). Each message appears as a
defaults factory followed by an `encode`/`decode` pair:

```js
function eL() { return { phoneNumber: "0", appId: 0, apiKey: "", ... } }

let m = {
  encode(r) {
    "0" !== r.phoneNumber && o.uint32(8).int64(r.phoneNumber),
    0 !== r.appId && o.uint32(16).int32(r.appId),
    "" !== r.apiKey && o.uint32(26).string(r.apiKey), ...
  },
  decode(r, o) {
    ...
    case 1:
      if (8 !== r) break;
      c.phoneNumber = a.int64().toString();
    ...
  }
};
```

Two facts make the extraction exact rather than heuristic:

* `o.uint32(N)` writes the **protobuf key** `N = (field_number << 3) | wire_type`,
  so both the field number and the wire type are literal in the code.
* `case K: if (N !== r)` repeats the same number on the decode path, and the
  assignment that follows gives the **exact field name**.

RPCs are equally explicit:

```js
let eH = {
  methodName: "StartPhoneAuth",
  service: eG,                                   // eG = { serviceName: "bale.auth.v1.Auth" }
  requestType:  { serializeBinary() { return m.encode(this).finish() } },
  responseType: { deserializeBinary(r) { return v.decode(r) } }
};
```

`tools/extract_protos.py` parses all 70 modules that contain generated code
across the eagerly loaded bundle and the 260 lazy chunks, resolves cross module
type references through the rspack export table, unwraps `google.protobuf.*`
wrappers, and emits `.proto` files.

**Result**

| metric | value |
|--------|-------|
| services | **52** |
| RPCs | **691** |
| message types | **2012** (2006 in the runtime schema after de-duplication) |
| enum families | 30+ (e.g. `AUTHSENDCODETYPE_*`, `PHONEACTIVATIONTYPE_*`) |
| `protoc` validation | `out/bale.proto` compiles with `grpc_tools.protoc` |

---

## 2. Endpoints

The web client picks a backend from build flags:

```js
isNext()  ? { grpc: "https://next-ws.bale.ai", ws: "wss://next-ws.bale.ai/ws/" }
: isBeta()? { grpc: "https://beta-ws.bale.ai", ws: "wss://beta-ws.bale.ai/ws/" }
:           { grpc: "https://maviz-ws.bale.ai", ws: "wss://maviz-ws.bale.ai/ws/?uid=<id>" }
```

All three serve the same services. `maviz-ws` is the default production
cluster, `next-ws` is the one this client uses by default.

| purpose | endpoint |
|---------|----------|
| unauthenticated RPC (login) | `POST https://{host}/{service}/{method}` |
| everything else | `wss://{host}/ws/` |
| file upload | `PUT <upload url>` (`multipart/form-data` + `Cookie: access_token=…`) |
| file download | `GET <download url>` |

---

## 3. Transport A - gRPC-web over HTTPS

Only the login methods are reachable this way; it is a normal gRPC-web unary
call:

```
POST /bale.auth.v1.Auth/StartPhoneAuth HTTP/1.1
Host: next-ws.bale.ai
content-type: application/grpc-web+proto
origin: https://web.bale.ai
app_version: 176652
mt_app_version: 176652
browser_type: 1
browser_version: 138.0.0.0
os_type: 3
mt_os_type: 3
...

<0x00><4 byte big endian length><protobuf message>
```

The response is a sequence of gRPC-web frames: data frames
(`flags=0x00`) followed by a trailer frame (`flags=0x80`) that contains
`grpc-status` / `grpc-message`.

**Errors are not in the body.** Bale answers application level errors with a
`grpc-message` response header and no payload, e.g.

```
grpc-message: PHONE_NUMBER_INVALID
grpc-message: phone auth limit exceeded
grpc-message: PHONE_CODE_INVALID
grpc-message: password needed for login
grpc-message: PHONE_NUMBER_UNOCCUPIED
```

---

## 4. Transport B - the WebSocket protocol

```
GET wss://next-ws.bale.ai/ws/ HTTP/1.1
Origin: https://web.bale.ai
Cookie: access_token=<JWT>
```

Frames are binary and each frame is exactly one `WsRequest` or `WsResponse`
protobuf message:

```proto
message WsRequest {
  Request          request          = 1;
  Ping             ping             = 2;
  HandshakeRequest handshakeRequest = 3;
}
message WsResponse {
  Response          response          = 1;
  UpdateEnvelope    update            = 2;
  TerminateSession  terminateSession  = 3;
  Pong              pong              = 4;
  HandshakeResponse handshakeResponse = 5;
}
```

### 4.1 Handshake

Immediately after the socket opens the client sends

```proto
HandshakeRequest  { int32 mkprotoVersion = 1; int64 apiVersion = 2; }
```

with `mkprotoVersion = 1` (the client's `ProtocolVersion` constant) and
`apiVersion = 176652` (the app build).

The server answers

```proto
HandshakeResponse { int32 mkprotoVersion = 1; int64 apiVersion = 2; int64 serverTime = 3; }
```

If `mkprotoVersion`/`apiVersion` differ from the client's own values the web
app forces an update, so a custom client must send the current build number.
`serverTime` is used for clock offset correction.

### 4.2 Requests and responses

```proto
message Request {
  RequestBody body      = 1;
  Ping        ping      = 2;
  HandshakeRequest handshake = 3;
}

message RequestBody {
  string   service    = 1;   // e.g. "bale.messaging.v2.Messaging"
  string   method     = 2;   // e.g. "SendMessage"
  bytes    payload    = 3;   // the encoded request message
  MetaList metadata   = 4;
  int64    request_id = 5;   // echoed back in ResponseBody.number
}

message MetaList { repeated ExtData meta_list = 1; }
message ExtData  { string name = 1; ExtValue value = 2; }
message ExtValue { string string = 1; int64 number = 4; }
```

`payload` is the raw protobuf of the RPC request message - because protobuf
embeds a sub message byte for byte, this is identical to declaring the field
as the concrete type.

```proto
message ResponseBody {
  Error  error  = 1;   // { int64 topic = 1; string message = 2; }
  bytes  result = 2;   // encoded response message of the RPC
  int64  number = 3;   // request_id
}
```

### 4.3 Keep-alive

```proto
message Ping { int64 ID = 1; }
message Pong { int64 ID = 1; }
```

The web client pings every **10 s** and gives up on a pong after **5 s**,
then reconnects with exponential backoff (100 ms → 3 s). The socket has a
3 s open timeout, and close code **4401** means "token invalid" (the app logs
the user out instead of reconnecting).

### 4.4 Updates

```proto
message UpdateEnvelope { bytes update = 1; }   // payload is an UpdateBody
```

`UpdateBody` is a 147-field union - field 4 is `messageSent`, 55 is a full
`message`, 162 `messageContentChanged`, 52815 `messages`, and so on. The
complete list is in `out/bale.proto` (`message Update`).

---

## 5. Authentication

`StartPhoneAuth` is the only call that is made **without** a token.

```proto
message Auth_StartPhoneAuth_Request {
  int64  phoneNumber     = 1;   // E.164 without '+', e.g. 989121234567
  int32  appId           = 2;   // web = 4, iOS = 2
  string apiKey          = 3;
  bytes  deviceHash      = 4;   // any stable UUID per install
  string deviceTitle     = 5;   // "Chrome_138.0.0.0, Windows"
  google.protobuf.StringValue timeZone = 6;
  repeated string preferredLanguages   = 7;
  ImeiList imeiList      = 8;
  int32  sendCodeType    = 9;
  repeated int32 options = 10;
}

message Auth_StartPhoneAuth_Response {
  string transactionHash = 1;
  bool   isRegistered    = 2;
  int32  activationType  = 3;   // 0 = code, 1 = password, 2 = future token
  bool   isImeiOk        = 4;
  int32  sentCodeType    = 5;
  google.protobuf.Int64Value codeExpirationDate = 6;
  int32  nextSendCodeType = 7;
  google.protobuf.Int64Value nextSendCodeWaitTime = 8;
  google.protobuf.Int32Value codeTimeout = 9;
  ...
}
```

App credentials found in the bundle (`module 23437`):

| platform | appId | apiKey |
|----------|-------|--------|
| web | 4 | `C28D46DC4C3A7A26564BFCC48B929086A95C93C98E789A19847BEE8627DE4E7D` |
| iOS | 2 | `D5E18686B5C7FA2EA2EC64BDE0A0EC6CD5E18686B5C7FA2EA2EC64BDE0A0EC6C` |

`sendCodeType` (`AUTHSENDCODETYPE_*`): `1` default, `3` SMS, `4` call,
`8` WhatsApp, `9` Telegram, `10` USSD, `13` flash call.

Then:

```proto
message Auth_ValidateCode_Request {
  string transactionHash = 1;
  string code            = 2;
  google.protobuf.BoolValue isJwt = 3;   // set to true to receive a JWT
  repeated string futureAuthTokens = 4;
  int32 language = 5;
}
```

The response carries `user`, `config` and `jwt`; the JWT is the value of the
`access_token` cookie used to open the WebSocket (24 h lifetime; the client
refreshes it through `GetJWTToken`).

If `activationType == 1` the account has two-step verification:

```proto
message Auth_ValidatePassword_Request {
  string transactionHash = 1;
  string password        = 2;
  google.protobuf.BoolValue isJwt = 3;
}
```

Unknown numbers answer `PHONE_NUMBER_UNOCCUPIED` from `ValidateCode`, after
which `SignUp { transactionHash = 1; name = 2; }` creates the account.

The complete login state machine in the web client is
`initial → otp → (2fa) → (register) → success`.

---

## 6. Metadata

Every request carries a `MetaList`. The web client sends the same key twice,
once plain and once with a `mt_` prefix:

```
app_version      176652          mt_app_version      176652
browser_type     1               mt_browser_type     1
browser_version  138.0.0.0       mt_browser_version  138.0.0.0
os_type          3               mt_os_type          3
session_id       <ms timestamp>  mt_session_id       <ms timestamp>
```

`browser_type`: chrome=1, firefox=2, safari=3, edge=4, opera=5, unknown=100.
`os_type`: ios=1, android=2, windows=3, linux=4, macos=5, unknown=100.

---

## 7. Services

52 services / 691 RPCs were recovered - the full table is in
[`docs/services.md`](services.md). The ones a messenger client needs:

| service | RPCs | notes |
|---------|------|-------|
| `bale.auth.v1.Auth` | 27 | login, sessions, 2FA, JWT refresh |
| `bale.messaging.v2.Messaging` | 43 | dialogs, history, send, read, folders |
| `bale.users.v1.Users` | 36 | profiles, contacts, privacy, avatars |
| `bale.groups.v1.Groups` | 51 | groups/channels, members, admin |
| `bale.presence.v1.Presence` | 11 | online/typing/last seen |
| `bale.story.v1.Story` | 24 | stories |
| `bale.meet.v1.Meet` | 30 | calls (LiveKit/SFU) |
| `bale.v1.Images` | 11 | thumbnails, stickers, image processing |
| `bale.v1.Configs` | 3 | client configuration |
| `bale.wallet.v1.Wallet` + `bank`/`bill`/`pfm`/`sap` | 13+17+8+15+16 | Bale's banking side |

### Messaging essentials

```proto
message Peer {           // Peer3 adds int64 accessHash = 3
  int32 type = 1;        // 1 = user/bot, 2 = group/channel
  int32 id   = 2;
}

// Messaging/LoadDialogs   (verified against the live server)
message Messaging_LoadDialogs_Request {
  int64 minDate = 1;              // -1 on the first page, then a date cursor
  int32 limit = 2;                // the web client uses 60 / 40
  repeated bytes optimizations = 3;   // incremental sync state, empty on cold start
  int32 dialogType = 4;           // folder id, 0 = all
  bool excludePinnedDialogs = 5;
  int32 archiveFilter = 6;        // 0 = ALL, 1 = archived only, 2 = exclude archived
}
message Messaging_LoadDialogs_Response {
  repeated Groups2  groups  = 1;
  repeated Users    users   = 2;
  repeated Dialogs  dialogs = 3;
  repeated UserPeers  userPeers  = 4;
  repeated GroupPeer  groupPeers = 5;
}

// Messaging/LoadHistory
message Messaging_LoadHistory_Request {
  Peer2 peer = 1; int64 date = 2; int32 loadMode = 4;
  int32 limit = 5; repeated bytes optimizations = 6;
}

// Messaging/SendMessage
message Messaging_SendMessage_Request {
  Peer2 peer = 1;
  int64 rid  = 2;                        // random 62 bit client id
  Message2 message = 3;                  // content union
  google.protobuf.Int32Value isOnlyForUser = 4;
  QuotedMessageReference quotedMessageReference = 5;
  Peer3 exPeer = 6;
  bool isSilent = 7;
  MessageId threadId = 8;
}

message Message2 {
  TextMessage textMessage = 15;          // { string text = 1; }
  DocumentMessage documentMessage = 4;
  ServiceMessage  serviceMessage  = 11;
  StickerMessage  stickerMessage  = 12;
  PollMessage     pollMessage     = 29;
  ...                                    // 30 variants, see bale.proto
}
```

`Dialogs` (the dialog entry) carries `peer`, `unreadCount`, `sortDate`,
`senderUid`, `rid`, `date`, the last `message`, `state`, `isMute`, ...

`History` (a message in a conversation) carries `senderUid`, `rid`, `date`,
`message` (the same `Message2` union), `state`, `quotedMessage`, `seq`,
`previousMessageId`, `editedAt`, `groupedId`, `replies`, ...

---

## 8. Client lifecycle (as implemented in `baleclient`)

```
login      StartPhoneAuth ──► ValidateCode ──► JWT
                                   │
                                   └─► (password) ValidatePassword
                                   └─► (new user) SignUp
connect    wss://<host>/ws/  Cookie: access_token=<jwt>
           ──► HandshakeRequest{mkprotoVersion:1, apiVersion:176652}
           ◄── HandshakeResponse{...}
loop       send WsRequest{request:{service, method, payload, metadata, request_id}}
           ◄── WsResponse{response:{result, number}}   (matched by request_id)
           ◄── WsResponse{update:{update: UpdateBody}}  (server push)
           every 10 s: WsRequest{ping:{ID}}
           close 4401 ──► token rejected, log in again
```

---

## 8.1 Field notes from a live session

Things that only became visible once a real account was used:

* `LoadDialogs` **must** be called with `minDate = -1` for the first page.
  The web client does `date === Number.MAX_SAFE_INTEGER && (date = -1)`.
  Sending `minDate = 0` is not "no cursor" - it is a date cursor in 1970 and
  the server answers with an empty `Messaging_LoadDialogs_Response`
  (this client hit exactly that bug and fixed it).
* A successful call with no data returns a response whose `result` field is
  simply absent (0 bytes), not an error frame. Do not treat that as a failure.
* `archiveFilter` 0 = `ARCHIVEFILTER_ALL`, 1 = `ARCHIVEFILTER_TRUE`,
  2 = `ARCHIVEFILTER_FALSE` (from `eO.$yA` in the bundle).
* `LoadHistory` takes `{peer, date, limit, loadMode, optimizations: []}`;
  `MessageRead` takes `{peer, date, exPeer: undefined}`.
* Access hashes for peers arrive in the response's `userPeers` / `groupPeers`
  lists, not inside the dialog entries - `Peer2 { type, id, accessHash }` is
  what `LoadHistory`/`SendMessage` expect.
* Bale JWTs are long lived (about a year), so `Auth/GetJWTToken` is only
  needed after a `SignOut` or a token revocation.
* `LoadHistory` uses the **same `-1` cursor convention** as `LoadDialogs`.
  `MessagesModule.openHistory` computes

  ```js
  mode = hasUnread || pinnedDate ? LISTLOADMODE_BOTH      // 3
                                 : LISTLOADMODE_BACKWARD  // 2
  date = pinnedDate ?? (hasUnread ? dialog.firstUnreadDate
                     : loaded.end ? lastMessage.date
                     : -1) || Number.MAX_SAFE_INTEGER
  ```

  so a cold open sends `{date: -1, loadMode: 2, limit: 20}`; sending
  `date: 0, loadMode: 0` returns a valid but empty history.
  `LISTLOADMODE_FORWARD = 1`, `_BACKWARD = 2`, `_BOTH = 3`.
* Dialogs do **not** contain names. The web client resolves them separately:
  `Users/LoadUsers({peers:[{uid, accessHash}]})` -> `.users`, and
  `Groups/LoadGroups({peers:[{groupId, accessHash}]})` -> `.groups`.
  `Messaging/LoadDialogs` only returns ids, unread counters and the last
  message - which is why a client that skips `LoadUsers` shows raw ids.

## 8.2 Files, media, avatars (read out of `modulesBuilder` / `UploadManager`)

The upload path is generated code that only exists in
`static/js/async/modulesBuilder.e7b98d256c.js`, which is why the first pass
missed it. The call is short and unambiguous:

```js
getUploadUrl(uid, expectedSize, name, mimeType, exPeer, sendType) {
  return this.api.GetNasimFileUploadUrl({
    uid, expectedSize,
    crc: "",                       // empty string, not 0
    name, mimeType, exPeer,
    sendType: sendType ? {type: sendType} : undefined,
    chunkSize: 0, compressedSize: 0,
    fileKind: FILEKIND_UNKNOWN     // 0
  });
}
```

`sendType` is only filled for photos (`SENDTYPE_PHOTO = 1`) and videos
(`SENDTYPE_VIDEO = 2`); every other file is sent with `sendType` **absent**.
The response is `{fileId, url, duplicate, chunkSize, blockSize}`:

* `chunkSize == 0` -> **single PUT**: `PUT url` with
  `Content-Type: multipart/form-data` and the raw file as the body.
* `chunkSize > 0`  -> **multi PUT**: slice the file into `chunkSize` blocks,
  `PUT url` each one with the headers `chunk: <index>` and `no: <total>`
  (60 s timeout per chunk, up to 3 in parallel), then finish with an *empty*
  `PUT` carrying `chunk: <total>, no: <total>`. gRPC `AlreadyExists` means the
  file is already on the server (deduplication by content hash);
  `DataLoss`/`NotFound` mean "start over".

Downloading goes through `Files/GetNasimFileUrl{file:{fileId, accessHash},
ciphertext:false}` -> `{fileUrl:{fileId, url, timeout}, hashes}` and the `url`
is fetched over plain HTTPS. The same `fileId`/`accessHash` pair is what a
`DocumentMessage` carries, so message media and avatars use one code path:

* message attachment -> `DocumentMessage{fileId, accessHash, fileSize, name,
  mimeType, thumb, ext, caption}`,
* avatar -> `Users.avatar.smallImage.fileLocation`,
  `Groups2.avatar.smallImage.fileLocation`.

The sending side is `Messaging/SendMessage` with
`message:{documentMessage:{...}}`; a photo sets
`ext:{documentExPhoto:{w,h}}`, a voice note `ext:{documentExVoice:{...}}`,
and the caption travels in `caption:{text}`.

## 8.3 Reactions and search

* `Abacus/MessageSetReaction{peer, rid, code, date}` and
  `MessageRemoveReaction` - this is the whole API, the emoji itself is the
  `code` string.
* `Abacus/GetMessagesReactions{peer, field_18, originPeer, field_34}` takes
  **two** `MessageId{date, rid, seq}` per call and answers
  `containers[{rid, date, reactions[{users, code, cardinality}]}]`.
* Live: `Update.reactionsUpdate = 222`
  `ReactionsUpdate{peer, rid, field_26:{users, code, cardinality}, reactionByMe}`.
* `Search/SearchMessages{query, optimizations}` where `query` is a `Query2`
  condition tree: `searchPieceText:{query}` alone searches everything,
  `searchAndCondition:{andQuery:[searchPieceText, searchPeerCondition]}` limits
  it to one chat. The response carries `searchResults[{result:{peer, rid,
  date, senderId, content, messageContainer}}]` **plus** `users` and `groups`
  for the senders - the sender names are not in the result itself.

## 8.4 Rendering Persian in a pure-Python GUI

The Tk front end (`baleclient/tkgui.py`) is Python only, and Tk draws a string
strictly left to right, glyph by glyph - it has no Arabic shaping and no bidi.
`baleclient/rtl.py` therefore does what the browser does for the web GUI:

1. **Shaping.** Each Arabic letter has up to four glyphs (isolated, final,
   initial, medial) in the Unicode Arabic Presentation Forms blocks
   (`U+FB50`-`U+FEFF`). The tables are *derived at import time* from
   `unicodedata` names (`ARABIC LETTER BEH INITIAL FORM` -> `U+FE91`), so there
   is no hand-maintained glyph table. Joining behaviour comes from the
   right-joining set (`ا آ أ إ د ذ ر ز ژ و ة ى`) plus the mandatory
   `لا` / `لأ` / `لإ` / `لآ` ligatures.
2. **Bidi.** The shaped run is classified per character (RTL letters, Latin
   letters, digits, neutrals), neutrals take the direction of their neighbours,
   Latin runs and numbers keep their own order, and the RTL runs are emitted in
   reverse. Digits and `. / : _ -` glued to Latin letters (a URL, a file name,
   `bale.proto.txt`) stay inside the Latin run, which is what keeps links
   readable inside a Persian sentence.

`visual("سلام 123 abc")` -> `"abc 123 ﻡﻼﺳ"`. The wire always carries the
original logical text; only the widget sees the presentation string.

## 9. Notes, gaps and honesty

* Message **names** are not present in the minified bundle (protoc drops them
  in this build). Names in the generated `.proto` are derived from the RPC that
  uses them (`Messaging_SendMessage_Request`), from the field that references
  them (`Peer`, `MessageId`, `TextMessage`), or - when neither exists - from the
  module and codec variable. Field **numbers, wire types, field names and
  enum constants are exact**.
* 29 field types could not be resolved (they point at generated code that the
  minifier inlined); the extractor marks them as `bytes`, which keeps decoding
  lossless but untyped. They are listed by rerunning
  `tools/extract_protos.py` and inspecting `schema.json`.
* `google.protobuf.Timestamp` and `Duration` share the same wire shape
  (`seconds`, `nanos`); the extractor names both `Timestamp`.
* No live server was used to build this document; every statement above is
  read directly out of the shipped client. The auth path is the only one that
  could be sanity-checked without an account, and the client reports server
  errors verbatim so mismatches are easy to spot.

## 10. Legal

This is an interoperability / research document produced for the
**try2reverse** challenge, which explicitly asks for the protocol of the
provided client to be reversed. Bale is a trademark of its owner; this project
is not affiliated with or endorsed by them. Do not use it to break the law,
spam, or access accounts that are not yours.
