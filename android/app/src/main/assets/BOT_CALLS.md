# 机器人语音与视频通话 API

弥星 2.4.2 / 后端 1.17.0。直接 REST 控制 + Webhook 推送，不提供通话事件轮询。支持用户拨打机器人，以及机器人向已允许来电的用户拨打语音或视频电话。

## 启用

1. 先按 BOT_API.md 创建机器人并验证公网 HTTPS Webhook。
2. 开发者在“我的 → 开发者机器人”点“启用机器人通话”。也可用个人账号凭证 PATCH `/api/developer/bots/{id}`，正文 `{"calls_enabled":true}`。默认关闭，已有文字机器人不会自动获得通话能力。
3. 用户主动添加机器人后，可以点语音/视频按钮拨打，默认允许机器人来电。用户可在聊天设置关闭；已经明确关闭的选择继续保留。未添加或已解除/拉黑的机器人不能来电。人类用户始终需要手动接听来电。
4. 开发者在自己的服务器运行 WebRTC 媒体程序；弥星负责连接信息、权限和中继，不替你运行大模型或媒体算法。音视频通过 WebRTC DTLS-SRTP 到达机器人媒体端，开发者可接收处理通话内容。Android 对机器人通话使用 relay ICE 策略，避免将本机直连候选提供给第三方；示例也只交换 relay 候选。连接信息由弥星托管中转；普通用户之间的加密和通话流程保持不变。

## 接口

全部使用 `Authorization: Bearer <机器人API凭证>`，CA 信任、限流和错误格式与 BOT_API.md 相同。

`POST /api/bot/calls` 发起：

```json
{"recipient":"12345678","client_id":"call-1001","session_id":"本次媒体进程生成的UUID","video":false}
```

`session_id` 请替换为随机生成的规范小写 UUID，例如 Python `str(uuid.uuid4())`；同一运行进程保持一致。`video` 是布尔值，true 表示视频。`client_id` 为 1–80 位字母、数字或 `._:-`，同一次拨号重试须沿用原值和 session_id。成功返回 `call`、`call_id`；已结束的相同请求返回 `call:null, ended:true`，不会重新振铃。

`POST /api/bot/calls/{call_id}/accept` 接听，正文 `{"session_id":"本进程UUID"}`。只有被叫机器人可以接听。重复接听不创建第二个媒体端；其他进程用不同 session_id 接听或发送信令会被拒绝。

`POST .../{call_id}/busy` 表示线路/模型繁忙，立即结束本通并推送 `call.ended`、`reason:busy`，客户端明确提示忙线；已结束后重复请求幂等成功。适用于模型预热、负载已满和上游限流。仅本机器人参与的通话可操作。

`POST .../{call_id}/reject` 拒接，`POST .../{call_id}/end` 挂断，正文 `{}`。仅本机器人参与的通话可操作；结束后重复请求幂等成功。

`GET /api/bot/ice`：有本机器人活动通话时取得 `iceServers`，包含短期 TURN/TCP 443 凭证。仅发 JSON 信令不会产生声音，媒体程序必须创建 RTCPeerConnection、添加音轨/视频轨并处理远端媒体。不能将音频字节 POST 到信令接口。

`POST .../{call_id}/signal`，正文示例：

```json
{"session_id":"本进程UUID","signal":{"type":"offer","sdp":"真实WebRTC引擎生成的SDP"}}
```

type 支持 offer、answer、candidate、restart。candidate 格式：

```json
{"session_id":"本进程UUID","signal":{"type":"candidate","candidate":"candidate:...","sdpMid":"0","sdpMLineIndex":0}}
```

SDP 最多 65536 个 UTF-16 单元；candidate 最多 4096、sdpMid 最多 128、mline index 0–32；序列化请求体最多 300000 字节。由真实 WebRTC 引擎生成内容。信令只允许在已接听的本次媒体进程中交换，服务端加密封装后转发给 Android。

`POST .../{call_id}/connected`：媒体引擎实际连接后上报，正文含 session_id。服务器需收到双方的 connected 才开始计算已接通时长。不要用 HTTP 200 冒充音视频已经接通。

`POST .../{call_id}/heartbeat`：已接听后约每 10 秒携带 session_id **上报存活**，不获取事件。30 秒未上报或媒体程序退出时结束通话；这是活动通话的存活上报，不是消息轮询。停止通话后不再上报。

`GET /api/bot/call` 仅用于启动后的状态确认/清理，返回本机器人活动通话；示例仅启动时查询一次，并结束上一个进程遗留的通话。新进程不能接管旧进程的媒体身份。

个人账号可 POST `/api/relationships/{机器人弥星号}/bot-calls`，正文 `{"allowed":true}` 或 false，设置自己的机器人来电权限。机器人凭证不能修改此项。删除/拉黑机器人或重新开始聊天身份会清除旧设置并拒绝来电；再次主动添加后使用默认允许；关闭许可会结束当前由该机器人发起的通话。

## Webhook

与消息回调相同的 HMAC 时间戳、事件 ID 和原始字节验签方式；先验签再入队，快速返回 2xx。**不要在 HTTP 回调应答前等待 ICE 收集、模型推理或媒体连接**。事件至少一次，按事件 id 去重；检查 call_id，丢弃已结束/过期通话的旧事件。

- `call.ringing`：用户拨给机器人，包含 call 和 user。机器人程序可 accept 或 reject。
- `call.waiting`：机器人拨号已发出，包含 call 和 user。
- `call.accepted`：对方已接听，包含 call。创建媒体连接并开始交换 SDP/ICE；机器人是 caller 时创建 offer，是 callee 时处理 offer 并返回 answer。
- `call.signal`：包含 call_id 和解密后的 signal，例如 `{"type":"offer","sdp":"..."}`。
- `call.peer_reconnecting` / `call.peer_reconnected`：对方消息连接短暂中断/恢复。
- `call.ended`：包含 call_id、reason、duration、video。reason=busy 表示忙线。停止心跳，关闭 RTCPeerConnection 和音视频轨，释放设备/模型资源。

外层还包含稳定 `id` 和 `bot_id`。call 包含 id、caller、callee、video、created、accepted 等公共字段；不会暴露 session_id。通话事件独立于普通消息队列，避免被积压消息拖延；连接信息在数据库中包装加密，活动事件通常最多保存 30 秒、结束事件 60 秒。短间隔退避最多六次，失败结束通话，不会把过期来电在几小时后重新推送。服务器重启后旧活动信令被丢弃，媒体程序的存活上报会失败并应关闭连接。

## 可运行媒体示例

bot-examples/call_bot.py 采用 [aiortc](https://aiortc.readthedocs.io/en/latest/api.html)。在开发者自己的 Linux/WSL 环境安装 `call-requirements.txt`，配置 QINGLIAO_BOT_TOKEN 和 QINGLIAO_WEBHOOK_SECRET，将 `/qingliao/webhook` 经自己的正常 HTTPS 反向代理到本地 9080，运行：

```
python call_bot.py
python call_bot.py --call 12345678
python call_bot.py --call 12345678 --video
```

后两条是主动拨号，接收用户必须在线并已允许机器人来电。程序会接收媒体但不落盘，发送轻微测试音与运动测试画面，打印不含 SDP、IP、密钥或消息正文的媒体统计。这是媒体连通示例，**没有内置 AI 对话、语音识别或语音合成**。你可以替换 Tone、Pattern 和 consume() 接入自己的音频/视频逻辑。

示例 Webhook 使用有界内存队列快速应答，适合活动通话演示；生产程序需结合自己的消息持久队列与监控。message.created 请接入已有文字机器人处理器。aiortc 1.15 没有公开 createOffer(iceRestart) 接口，示例不宣称完整 ICE 断网恢复；收到需重启的请求或失败状态时会结束连接，之后可以重新拨打。协议本身保留 restart，支持该能力的媒体引擎可以实现完整重协商。

## 限制和撤销

当前每个机器人同时一通，全服务最多两通机器人通话；单次最长 30 分钟。机器人主动拨号最多每分钟两次、每天 50 次，对同一用户 10 分钟内最多三次，另受公共信令与 HTTP 限额。未接听或未建立媒体有 45 秒截止。音视频走现有中继，媒体程序在开发者服务器运行，不要将 aiortc/FFmpeg 安装到这台小内存弥星后端来替所有开发者处理媒体。

停用机器人、关闭通话能力、重置 API 凭证、重新配置 Webhook、用户拒绝/挂断/关闭来电权限/解除关系，都会终止相关通话权限。已进入开发者媒体端的音视频内容不能远程收回。公开 API 不是独立安全审计承诺。

## 忙线反馈（1.17.0）

发起时任一方已有活动通话返回 HTTP409，JSON有 `code:self_busy`（发起方正在通话）或 `code:call_busy`（对方/线路繁忙），并带 peer、video、invite_id。WebSocket发起的错误同样带这些字段，连接继续可用，原通话不受影响。Android清理本次拨号等待，显示具体原因，并在当前聊天提供手动“重新拨打”；不会自动反复拨号。一般限流仍保留原有429防护。

Miko另运行本机Whisper识别、Agnes对话和Qwen3-TTS 1.7B Base akari2克隆流式PCM。来电在模型预热、消息处理容量或预算不足时直接busy，避免等待45秒；通话中遇到上游繁忙会尝试播报后busy结束。视频提供标明AI虚拟形象的Miko角色图，并可理解对方当前摄像头画面；不调用电脑麦克风或摄像头，不落盘录制通话。详见独立Miko接入说明。
