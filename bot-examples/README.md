# 接入示例（无轮询）

Python 3.10+，只使用标准库，无需安装 SDK。完整协议见同包的 BOT_API.md。

上面指文字与文件示例。语音/视频通话使用 call_bot.py，需安装 call-requirements.txt，并按 BOT_CALLS.md 启用机器人通话、配置 Webhook 与用户来电权限。该程序在开发者机器上处理 WebRTC，接收媒体不落盘并发送测试音/测试画面；可替换为自己的业务媒体逻辑，不含 AI 模型。消息事件可继续交给已有文字处理器。信令仍走直接 API + Webhook，没有事件轮询。

1. 在 App「我的 → 开发者机器人」创建机器人，保存 API 凭证。
2. 准备自己的 HTTPS 回调地址，将 `webhook_bot.py` 放在其反向代理后面。程序默认监听 `127.0.0.1:9080/qingliao/webhook`。不要直接将 Python 调试服务器暴露公网；反向代理需设置请求体不超过 900000 字节、连接与请求超时、并发和速率上限。
3. 在 App 配置公网回调 URL，保存 Webhook 签名密钥，分别以 `QINGLIAO_BOT_TOKEN`、`QINGLIAO_WEBHOOK_SECRET` 环境变量提供给程序，然后运行 `python webhook_bot.py`。程序不会打印密钥或消息正文。
4. App 点「验证回调」，通过后用户可搜索机器人弥星号并主动添加。发普通文字会收到回复，发 `/card` 会收到 HTML 卡片。

`webhook_bot.py` 先验证原始请求签名和时间，将事件写入本地 SQLite 后应答，独立工作线程处理消息并用原消息 ID 生成稳定回复 ID。SQLite 保存的是你收到的明文，请保护程序目录、磁盘、备份及环境变量；按你的隐私政策处理数据保留。处理失败最多重试八次，失败行保留以便运营者检查；它只是接入示例，生产业务应接入你自己的任务队列和监控。

也可单独使用 `bot_api.py` 的 `BotAPI.send_text`、`send_html`、`send_file`。`send_file(..., kind='image')` 发图片，`kind='voice'` 发语音，默认发文件。每次业务调用提供自己的唯一 client_id；网络不确定时重试沿用原 ID，避免重复消息。文件上传可单独调用接口后保存 file_id，避免发送重试时反复上传。

`server_ca.crt` 是弥星 API 的公开信任证书，示例保留 TLS 和主机名校验。它不用于给你自己的回调站点签证书；你的回调站点需要正常的公网受信任证书。

可运行 `python test_example.py` 验证本地 challenge、签名、过期拒绝和去重；该检查不会请求弥星或发送外部消息。
