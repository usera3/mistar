# 参与开发

欢迎通过 Issue 报告问题，或提交 Pull Request。请说明触发步骤、预期与实际行为、Android 版本及构建版本；界面改动请附使用合成内容的截图。

1. Fork 仓库，在自己的分支修改。
2. Python 3.11 安装 `backend/requirements.txt` 后运行 `python tools/run_backend_tests.py`。
3. 使用 JDK 21、Android SDK 36，在 `android/` 运行 `./gradlew :app:assembleDebug`。
4. 运行 `python tools/check_public_tree.py`；提交前建议执行 `gitleaks git --redact`。

不要提交 `.env`、SMTP/机器人/模型凭据、Android 签名密钥、私钥、身份备份、数据库、用户内容、访问日志或本机路径。测试使用隔离临时数据库和合成账号；不要向真实用户发送测试消息、验证码、电话或媒体。

保持协议、账号隔离、端到端加密、现有客户端更新验证及数据库迁移兼容。语音消息“转文字”直接启动并在原消息下显示状态；屏幕共享保留 Android 系统授权。公共广场默认关闭，不在修复中顺手开放。

安全漏洞请使用 GitHub 的私密漏洞报告入口，不要在公开 Issue 中粘贴可用凭据、私人聊天或攻击真实服务的细节。具体加密边界见 [SECURITY.md](SECURITY.md)。

项目原创代码采用 MIT；已有第三方源码保留自己的版权与许可，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
