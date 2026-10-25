# 验证与测试资产

## 后端

安装 `backend/requirements.txt`，运行 `python tools/run_backend_tests.py`。20 套测试分别在独立进程、临时数据库中验证消息、密文边界、邮箱验证、好友与动态权限、管理员、媒体、配额、机器人、通话状态及更新发布。另运行标准库 Webhook 示例的签名/去重测试。不会连接生产 SMTP 或操作生产账号。

测试公开/好友可见范围的用例显式在临时环境开启测试广场；`test_plaza_closed.py` 自行关闭。正常部署与源码默认 `QINGLIAO_PUBLIC_PLAZA=0`，测试配置不会修改生产服务。

## Android

`android/app/src/androidTest` 仅编译为独立测试 APK。`CryptoInstrumentation` 的 `mode` 可选择 `messageInteractions`、`voiceText`、`updates`、`screenShare` 等隔离场景。主包不包含测试入口或合成账号。需要自有模拟器，并为 `release` 主包和测试包提供相同的测试签名。

部分历史场景（例如 `brandSeed`/`brandVerify`）验证两个特定已签名版本间的数据保留；更新测试需要外部夹具 APK，不能直接以普通 debug 包运行。`voiceTextLive` / `dictationLive` 会把公开样本发送到第三方识别服务，只应明确选择运行；默认的假服务场景在本机拦截 HTTP。

### 资产来源

- `android/app/src/androidTest/assets/dictation/funasr_zh.wav`：FunASR 仓库 `runtime/funasr_api/asr_example.wav` 的 WAV 头规范化版本。其 PCM 样本已逐字节与上游比较一致（单声道、16-bit、16 kHz、88747 个样本）。
- `android/app/src/androidTest/assets/voice-text/public-speech.m4a`：相同公开语音样本的 AAC 测试版本。
- `python-fixtures.json`：合成协议向量，使用固定测试标量和公开的协议引导常量，不含用户会话或账号凭据。Gitleaks 例外仅匹配这个文件中确切的公开常量行，不跳过整个文件。
- `backend/testdata/`：合成视频与音频容器，用于大小、时长、封面和权限测试。
- `docs/images/`：自有模拟器的空登录页截图，没有聊天或账号资料。

FunASR 与 Concentus 的许可见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。测试样本不在正式主 APK 的 assets 中。公开仓库不包含本机 Miko 的角色图谱、图库、模型或语音克隆参考音频。
