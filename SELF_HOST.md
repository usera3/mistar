# 自行部署

本文面向独立部署。仓库中的 `cn.qingliao.app`、`/xingyu/`、`qingliao` 等内部名称用于已有协议兼容；公开产品名称为弥星（Mistar）。不要把示例占位符当作可用凭据，也不要复制其他运营者的数据或签名。

## Python 后端

建议 Linux / Python 3.11。创建专用系统账号 `qingliao`，将后端源码安装到 `/opt/qingliao`，使用独立虚拟环境安装 `backend/requirements.txt`。源码与运行数据分开：源码由管理员拥有，数据目录 `/var/lib/qingliao` 仅服务账号可读写。

`backend/deployment/qingliao.service` 使用 `/etc/qingliao/environment`；复制 `backend/.env.example` 并填写自己的参数，设置为 root 拥有、`0600`，不要放进网站目录或 Git。模板不由 dotenv 自动加载。若使用 `/opt/qingliao-bot-runtime` 虚拟环境，安装 `backend/deployment/bot-runtime.conf` 作为服务 drop-in；也可将服务的解释器路径调整到自己的虚拟环境。

必须配置自己的 `SMTP_USER` / `SMTP_PASSWORD`、独立随机 `OTP_SECRET` 和 `TURN_SECRET`。例如运行 `python3 -c 'import secrets; print(secrets.token_urlsafe(48))'` 为每项分别生成随机值，再私密保存。`PUBLIC_IP` 使用自己的服务器公网 IP，`QINGLIAO_PUBLIC_PLAZA=0` 保持公共广场关闭。默认强制邮箱验证和 E2EE，不要为上线绕过验证。

服务首次启动创建自己的 SQLite 表和机器人主密钥；将数据目录、主密钥及配置一起做私密备份。后续升级保留数据，不用旧数据库覆盖新增消息或账号。

## HTTPS 与 WebRTC 中继

部署模板见 `backend/deployment/`。HTTP 后端只监听 `127.0.0.1:8765`，由 nginx 提供 TLS、WebSocket、请求体/连接/速率限制。`QINGLIAO_TRUST_PROXY=1` 只在后端不可从公网直达、nginx 按模板清除伪造转发头并传递真实 IP 时启用。

当前配置用 nginx stream 在 TCP 443 分流 HTTPS 与 TURN：`nginx-http.conf` 和 `nginx-stream.conf` 是成对模板，涉及 PROXY protocol 及去头中继。请一并阅读，使用自己的域名、证书和网络地址。不要只将 443 改成普通 HTTPS 监听而丢失中继路径。TURN 模板里的 `REPLACE_WITH_...` 必须替换；公网/内网映射与端口需按自己的网络设置，继续保留私网目标拒绝及配额限制。

Android 和 `bot-examples/server_ca.crt` 内的证书是现有公开服务的**公有信任证书**，不是私钥。自部署必须改为自己服务的证书/信任链，保持 TLS 和主机名校验；不要关闭证书验证。服务私钥只存服务器受限位置。

## 客户端与机器人服务地址

- Android `Hub.SERVER`：`android/app/src/main/java/cn/qingliao/app/QingApp.kt`。
- Android `android/app/src/main/assets/server_ca.crt`：替换为自己的公开信任证书。
- Python 示例 `bot-examples/bot_api.py`：默认 API 源，以及同目录 `server_ca.crt`。
- `backend/server.py` 的 `PUBLIC_IP` 通过环境提供；nginx、TURN 使用自己的地址与证书。

默认的公开下载地址不是自部署的发布权限。若为自己的客户端提供更新，必须同时调整 Android `AppUpdates.kt` 中的页面/下载源/签名信任、后端 `releases.py` 的源/证书指纹/包名验证及发布工具中的对应白名单，修改 `distribution/` 的链接和元数据检查，并用自己的签名构建。不能用官方证书指纹配合自己的 APK，不能取消签名检查。

独立品牌建议使用自己的 applicationId。此时还要同步 FileProvider authority（Manifest 与调用处）、版本清单包名及安装校验；Kotlin namespace 和加密协议字段不等同于 applicationId，不要盲目全局替换。官方签名密钥不在仓库中，独立签名无法覆盖官方安装，原设备聊天身份也不应通过卸载来“解决”签名差异。

## 管理员

在服务完成首次建表后，服务器 root 可运行：

```sh
sudo python3 /opt/qingliao/deployment/create_admin.py \
  --database /var/lib/qingliao/qingliao.sqlite3 \
  --username your_admin \
  --credentials /root/mistar-admin-credentials.json
```

工具创建专用新账号，将初始凭据写入受限文件，拒绝提升现有账号或覆盖凭据。请私密交付，首次登录必须改密码。不要提交生成文件。内容审核与账号处理边界见 [ADMIN.md](ADMIN.md)。

## 会员支付

支付默认关闭，先按 [MEMBERSHIP.md](MEMBERSHIP.md) 配置自己的网关、商户、私有签名密钥、固定回调/返回来源及经过验证的HTTPS代理。原生 `MemberUi.GATEWAY` 默认固定官方网关，自部署时同步调整白名单。部署完整15个运行模块，并为所售存储权益预留容量；不要绕过现有磁盘、全站及单文件限制，也不要把支付私钥或运行数据提交源码。

## 机器人示例

通用发送与 Webhook 示例见 [bot-examples/README.md](bot-examples/README.md)。自己的回调需正常公网受信任 HTTPS，服务器会拒绝私网/回环回调地址。机器人和媒体程序运行在开发者自己的机器，API 凭据仅通过环境变量提供。模型、人设资料、语音模型与私人图库由开发者独立配置，本仓库不包含个人 Miko 部署。
