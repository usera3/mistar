# 弥星下载与版本更新

固定下载页：https://api.claudcode.xyz/xingyu/download/

固定 APK 地址：https://api.claudcode.xyz/xingyu/releases/latest.apk

## 使用

先从固定地址覆盖安装弥星 2.8.0。原版客户端没有更新模块，需要这一次手动升级。不要先卸载，以保留本机聊天记录、账号和加密身份。

此后“我的 → 检查更新”可以查看更新内容、下载、取消、重试、安装，以及复制或分享下载地址。下载完成后点击“安装更新”，由系统安装器确认；如系统要求，先为弥星允许安装未知应用，再返回点安装。通话中暂不启动更新。更新通知可关闭，系统通知权限也由用户控制。

在线连接收到 `app.update` 事件后检查新版并发出版本通知；同一版本只提醒一次，主页保留更新入口。每次 WebSocket 重连会补发当前版本提示。打开应用时也检查，普通前台恢复检查最短间隔 15 分钟，没有定时轮询。用户主动“检查更新”立即检查。发布与在途检查相遇时，最多延后补查一次。

未接入手机厂商推送/FCM，不能承诺应用被强行停止、离线或系统彻底回收后立即弹通知；下次打开或重连后补查。不会静默下载或静默安装，不强制更新。

## 安全与部署

聊天/API、账号与媒体仍在日本服务器。`api.claudcode.xyz` 只为官方下载页面及 APK 提供系统可信 HTTPS，既有 Miko 回调和其他服务保持独立。

- 更新使用独立系统 TLS 客户端、固定源和固定版本文件路径，禁重定向，不发送弥星令牌、Cookie、聊天信息或本机文件。
- 元数据最多 8 KiB、检查最多30秒；安装包最多 128 MiB、下载总时限10分钟，连续30秒无数据则超时。下载严格检查声明大小和 SHA-256，拒绝多余/截断数据；再检查包名、版本代码和原始签名。拒绝降级和其他应用/签名，不执行更新说明内的 HTML。Android 9 查询 APK 签名同时请求 `GET_SIGNATURES` 与 `GET_SIGNING_CERTIFICATES`，确保实际收集证书。
- 安装包只在应用私有 `cache/updates/` 暂存，以有界 `.part` 和单个已验证 APK 保存；离开下载页取消在途下载，进程重启清理缓存。FileProvider 仅额外开放该目录，并只给系统安装器临时读取权限。
- 版本发布需要服务器操作系统管理员。没有客户端或机器人上传版本、指定下载地址、推送任意内容的 API。后端只读根用户拥有的 `/etc/qingliao/release.json`，拒绝无效/回退版本，保留上次有效版本。
- 网站目录 `/var/www/xingyu/{download,releases}`，无目录列表、无上传方法。限速与连接限制独立于聊天。APK 可 HEAD/范围下载；元数据与 `latest.apk` 禁缓存。
- Japan 后端为 1.19.0，共十四个运行模块，新增 `releases.py`。广场继续关闭，不改动原内容、账号权限、E2EE、TURN 443 或机器人沙箱。

## 发布下一版本（维护者）

1. 增加 Android `versionCode`/`versionName`，使用原有受限签名密钥构建，先完成隔离测试。不得把签名密钥、管理员密码、SMTP/机器人密钥放到公开目录或源码包中。
2. 使用 `backend/deployment/make_release.py` 校验真正的 APK 包名、版本及签名并生成清单；更新说明为 JSON 字符串数组。该工具需要本机 `aapt`、Java 与 `apksigner.jar`，不读取发布签名密码。
3. 通过既有 SSH 管理通道把清单和 APK 上传到下载服务器受限暂存目录。以 root 运行 `deployment/install_release.py 清单.json 安装包.apk /var/www/xingyu/releases`。校验大小/散列后，依次原子发布不可覆盖的版本 APK、`latest.apk` 符号链接和 `latest.json`。先验证公网完整下载散列。
4. 同一份清单上传日本服务器，以 root 运行 `/opt/qingliao/deployment/install_release.py --feed 清单.json /etc/qingliao/release.json`。文件 root 拥有，0644，服务用户只有读权限。随后 `systemctl kill --signal=SIGUSR1 --kill-who=main qingliao`，后端加载并向已认证在线会话发送一次更新提示，离线会话重连补发。无需重启服务，也不需要轮询。
5. 验证 `/api/app-release` 与网站 `latest.json` 完全一致、三个服务正常、广场仍关闭。重复同一清单不会重复推送；需要修复版本时发布更高的版本代码，不覆盖已有版本 APK、不伪造更低代码。


## 验证范围

20 套隔离后端测试覆盖实时推送、离线重连、错误/过大/回退清单、只读接口，以及管理员、广场、消息、媒体、机器人与通话回归。API35 手机和 API28 安卓手表各验证原生更新 UI、通知去重、下载、散列/大小/包名/版本/签名检查、取消、失败重试和系统安装器。使用仅限自有模拟器的已签名高版本夹具，不向真实用户发测试聊天、打电话或上传测试媒体。没有真机测试。

系统安装与通知规则参考 [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)、[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。
