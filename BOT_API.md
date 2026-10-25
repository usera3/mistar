# 弥星机器人 API v1

机器人采用 **REST 发送 + Webhook 主动推送**，没有轮询接口。机器人是第三方服务联系人；用户须在新版 App 中确认隐私说明并主动添加。机器人不能主动申请好友、访问其他聊天或发布动态。开发者可单独启用语音/视频通话，已添加用户默认允许来电，可在聊天设置关闭；真人仍需手动接听，详见 BOT_CALLS.md。

机器人聊天由弥星服务器解密、中转给开发者，不能称为用户间端到端加密。普通用户之间的消息继续端到端加密。请向使用者说明你如何处理、保存和删除收到的数据。

## 创建和接入

1. 安装弥星 2.4.0，登录已验证邮箱的个人账号，打开「我的 → 开发者机器人」。
2. 创建机器人并保存仅显示一次的 API 凭证。每个账号最多 3 个，全服务暂限 50 个。
3. 在自己的服务部署 Webhook 接收程序，准备公网 HTTPS 443 地址和受信任证书。不支持内网地址、自签证书、重定向、URL 查询参数或自定义端口。
4. 在 App 配置 Webhook，保存仅显示一次的签名密钥。将它写入接收程序的环境变量，再点「验证回调」。程序须验签并返回 challenge。
5. 将机器人 8 位弥星号交给使用者，使用者搜索后点「同意并添加」。

API 根地址：`https://47.245.2.254`。本服务使用随包提供的 CA；调用时配置 `--cacert server_ca.crt` 或相应语言的 CA 信任参数，保留正常的主机名验证。不要关闭 TLS 校验。开发者资料包包含 CA、完整说明和 Python 标准库示例，无需机器人 SDK、密钥算法或轮询进程。

所有机器人请求携带 `Authorization: Bearer <API凭证>`。仅使用 HTTPS。凭证不能用作个人账号登录；数据库只保存凭证摘要。App 可停用机器人或重置凭证；重置不会改变机器人聊天身份。回调密钥与 API 凭证相互独立，重新配置 Webhook 会生成新密钥并要求重新验证。

## 发送消息

`POST /api/bot/messages`，JSON：

```json
{"recipient":"12345678","client_id":"reply-1001","type":"text","text":"你好"}
```

`client_id` 必填：1–80 位 ASCII 字母、数字或 `._:-`。同一机器人内唯一；重试必须沿用原值。成功响应包含 `id`、`client_id`、`created`；重复请求返回原消息，已删除消息返回 `removed: true`，不会重新出现。

`type` 支持：

- `text`：`text` 最多 5000 个 UTF-16 单元。
- `html`：`text` 是 HTML 源码，最多 50000 个 UTF-16 单元。App 在隔离的静态卡片中展示；禁用 JavaScript、外部网络、iframe、导航和本地文件访问。适合内联样式、表格、文字、内嵌 data 图片；不能运行交互脚本。
- `image`、`voice`、`file`：先上传，再提供 `file_id`。图片必须是 image MIME，语音必须是 audio MIME。

语音可通过 `text` 提供显示标签，例如 `2″`；省略时显示“语音”。不要把尚未获取的时长填写成真实测量结果。

```json
{"recipient":"12345678","client_id":"card-1001","type":"html","text":"<div style='padding:16px;background:#eaf1ff;border-radius:16px'><h3>今日提醒</h3><p>记得喝水。</p></div>"}
```

## 上传和下载附件

`POST /api/bot/uploads?recipient=12345678&name=photo.png`：请求体是原始文件字节，**不是 multipart，也不是远程 URL**；`Content-Type` 为真实 MIME，例如 `image/png`。name 需要 URL 编码，不得含路径、控制字符或双向覆盖字符。不能上传空文件，最大 25 MiB。上传成功返回 `id/name/mime/size`。

```json
{"recipient":"12345678","client_id":"image-1001","type":"image","file_id":"上传返回的32位编号"}
```

附件在弥星存储中仍为加密文件，原文件名和附件密钥放在加密消息内。只有本机器人上传给同一联系人的附件可被发送。未发送附件 24 小时后回收。原始文件请由开发者自行维护，发送失败不要反复重新上传大文件。

`GET /api/bot/files/{id}`：用机器人凭证下载消息中的附件，返回解密后的字节，按附件下载处理。必须是本机器人参与、关系仍有效的聊天中的附件。保存时自行指定安全路径，不要直接采用网络提供的文件名。不要执行收到的 HTML 或文件。

`GET /api/bot/me`：本机器人的资料。

`GET /api/bot/contacts`：主动添加本机器人的联系人。没有任意搜索、好友申请、个人账号设置或其他用户聊天权限。

## Webhook 验签与应答

弥星向已验证地址 POST UTF-8 JSON。请求头：

```
X-Qingliao-Timestamp: Unix秒数
X-Qingliao-Event-Id: 事件UUID
X-Qingliao-Signature: sha256=小写十六进制HMAC
```

签名输入是 `timestamp + "." + event_id + "." + 原始请求体字节`，算法 HMAC-SHA256，密钥为 Webhook 签名密钥。**先用原始字节验签再解析 JSON**，采用常量时间比较；拒绝与接收时间相差超过 300 秒的请求。正文 id 必须等于请求头 event id。不要依赖来源 IP 作为唯一验证。

验证事件：

```json
{"id":"事件UUID","event":"webhook.verify","bot_id":"机器人弥星号","challenge":"随机字符串"}
```

验签成功后返回 HTTP 200 与 `{"challenge":"收到的原字符串"}`。

消息事件：

```json
{"id":"稳定事件UUID","event":"message.created","bot_id":"机器人弥星号","message":{"id":"消息UUID","sender":"用户弥星号","recipient":"机器人弥星号","created":1792300000000,"client_id":"用户发送标识","type":"text","text":"你好"}}
```

附件消息包含 `message.file`（id、name、mime、size）和 `message.file_path`（站内下载路径）。不会向开发者暴露其他人的私钥或附件加密密钥。HTML 是源代码字符串，不要在自己的管理后台直接插入为可执行 HTML。

成功处理后返回任意 2xx，响应体不超过 4 KiB。生产程序建议先将事件**可靠入队**，立即应答，再异步处理；回调总超时 6 秒。不要在应答前等待大模型或大文件下载。

事件至少投递一次：用 `event.id` 在你自己的数据库中做幂等处理；自动回复可使用 `client_id="reply-"+message.id`。成功后不再主动发送同一事件，但网络断开可能使成功应答丢失。并行或重试期间不要假定严格顺序，按 created/id 排序。

失败重试间隔约 5 秒、15 秒、1 分钟、5 分钟、15 分钟、1 小时、6 小时；8 次失败后转为失败状态，App 可手动重试。队列重启后保留，事件最长保存 24 小时；每机器人最多 100 条、全服务最多 500 条。满队列时拒绝新入站消息并提示稍后重试。已撤回、机器人侧已清空、删除好友或拉黑的尚未投递消息会被丢弃。已经交给开发者的数据无法由弥星收回。

## 限额与错误

消息 90 条/分钟、20 条/10 秒，上传 60 次/小时，单文件 25 MiB；还受现有账号存储、日流量、全局并发与反滥用限制。Webhook 出站只使用公网 HTTPS，不跟随重定向，每次连接重新检查 DNS 实际地址；不代理下载外部图片 URL。

错误 JSON 为 `{"error":"说明"}`。401 表示凭证无效、已撤销或停用；403 表示没有权限/联系人关系；409 表示回调未验证或发送标识冲突；413 过大；429 限流（尊重 Retry-After）；507 存储限制。429/网络失败应退避，使用相同 client_id 重试；其他 4xx 先修正请求。

## 运维

管理接口使用**个人登录凭证**，不能使用机器人凭证：`GET/POST /api/developer/bots` 查看或创建（nickname、description）；`PATCH /api/developer/bots/{id}` 编辑（nickname、description、enabled、calls_enabled）；`POST .../{id}/rotate` 重置 API 凭证；`PUT .../{id}/webhook` 配置（url）；`POST .../{id}/verify` 验证；`POST .../{id}/retry` 重试失败消息。创建返回 HTTP 201；token 或 webhook_secret 只在相应创建/重置响应中显示。列表仅包含自己的机器人与回调状态，不返回密钥。

机器人私钥与回调密钥在数据库中使用 Tink AEAD 包装，主密钥单独保存在服务数据目录的 `bot-master.key`（0600，目录0700）。运维必须将数据库与此文件一起做私密备份；丢失主密钥将无法处理机器人历史和回调密钥。不能将该文件、服务器环境变量、个人密钥、API 凭证或 Webhook 密钥放进源码包。

协议原语沿用 [Tink HPKE](https://developers.google.com/tink/exchange-data) 和 [Streaming AEAD](https://developers.google.com/tink/encrypt-large-files-or-data-streams)。它们提供加密原语，本项目的机器人中转边界、授权和回调协议由弥星实现；不声称经过独立安全审计。
