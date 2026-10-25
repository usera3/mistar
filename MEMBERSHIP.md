# 弥星会员与支付（2.10.0 / 后端1.20.0）

“我的 → 弥星会员”展示方案、权益、有效期及订单记录。价格在服务端固定：月卡9.90元/30天、季卡25元/90天、年卡88元/365天。用户主动创建订单，再打开支付宝付款页确认付款；没有自动续费或代用户扣款。

会员提供500MiB云存储、每日256MiB上传额度、会员标识与星光头像框。单文件25MiB、并发/磁盘/全站容量/滥用防护继续有效。到期后回到普通上传额度，既有文件不因会员到期而删除，超额时可继续下载和清理。续费从 `max(到账核验时间, 当前到期时间)` 顺延所购天数。

## 到账与订单

接入独立部署的 [PaymentSystemBasedOnAlipayAppBillInquiries](https://github.com/gao-gao-zai/PaymentSystemBasedOnAlipayAppBillInquiries)，使用固定HTTPS Epay API。网关按支付宝账单识别到账，可能有延迟或同金额排队。本接入使用独立商户，额外加价额度为0，最多88元/单。

客户端只提交方案ID和随机客户端标识，不提交可决定成交价、天数或权益的字段。后端先持久化订单及方案/金额/天数/商户/回调地址/随机绑定值快照，再签名创建网关订单。失败重试沿用原业务订单和请求内容，避免不确定网络造成重复下单；续费另起新的客户端标识。

回调必须验证旧版Epay所需的MD5签名，并核对商户、订单号、交易号、金额、支付类型、商品名、随机绑定值及 `TRADE_SUCCESS`。严格拒绝重复字段、未知字段、歧义分隔符、超大内容及不同账号查单。支付标记和会员续期在同一SQLite事务中提交；重复回调、重启与查询补偿不会重复增加天数。

固定HTTPS查询可在用户返回App或主动点“查询付款结果”时补查；核对服务端查询结果的订单、交易号、金额、绑定值和成功状态。App不定时轮询，到账后通过已有已认证WebSocket刷新自己的会员状态。通知在有界后台任务中发送，不阻塞到账确认或通话维护。回到网页或收到客户端传来的“成功”字段均不能开通会员。

手机可打开网关付款页；手表可显示本订单二维码，用手机扫码打开该付款页。二维码不含弥星登录凭据。金额与收款人以付款页展示为准，用户自行在支付宝确认。原生支付页、订单接口和所有后台任务继续绑定打开时的账号。

本模块没有自动退款或用户间余额/转账接口。支付订单由后端及支付平台处理，不属于私人聊天端到端加密范围；订单仅向本人开放，资料中的公开会员标志不公开金额或到期时间。

## 服务端配置

仅在服务器受限EnvironmentFile设置，不能放进APK、GitHub、公开目录或日志：

```text
MISTAR_PAY_ENABLED=1
MISTAR_PAY_GATEWAY=https://your-gateway.example.com
MISTAR_PAY_PID=your-merchant-id
MISTAR_PAY_KEY=your-private-merchant-signing-secret
MISTAR_PAY_NOTIFY_URL=https://your-public-origin.example.com/xingyu/payments/notify
MISTAR_PAY_RETURN_URL=https://your-public-origin.example.com/xingyu/payments/return
```

可用 `MISTAR_PAY_PREVIOUS_KEY` 暂存轮换宽限期的旧密钥，确认旧订单结清后删除。停用新下单不停止已配置密钥的合法旧回调确认。网关商户允许来源填写notify/return的HTTPS根origin，关联自己的已配置支付宝账户，关闭加价。支付宝私钥留在网关，不复制给客户端或本后端。

`backend/deployment/nginx-membership*.conf` 只代理精确回调路径与静态返回页；跨服务器代理验证固定API的TLS证书，不关闭校验。部署时使用自己的域名和公有信任证书。不要把所有私有API作为回调代理开放。后台仍有15个运行模块，新增 `membership.py`；数据库迁移只新增订单/会员表，不替换已有用户或聊天表。

## 接口

以下本人接口均要求普通账号Bearer会话：

- `GET /api/membership`：固定方案、支付可用状态和自己的会员权益。
- `GET /api/payments/orders`：自己的订单，`before=时间戳:订单号`可分页。
- `POST /api/payments/orders`：JSON仅 `plan_id`、UUID格式 `client_id`。
- `GET /api/payments/orders/{id}`：本人订单。
- `POST /api/payments/orders/{id}/retry`：重试同一创建请求。
- `POST /api/payments/orders/{id}/refresh`：有界、节流的服务端到账补查。

`POST /api/payments/notify` 是独立的公开表单回调，仅在验签、核对与事务提交后返回纯文本 `success`；不接受客户端成功声明。生产不提供伪造到账或管理员测试授予会员的API。

## 验证

`backend/test_membership.py` 在临时数据库和假网关验证金额/参数篡改、重放、重复和歧义字段、跨账号、未知付款地址、事务失败、重复/晚到/先于创建响应的到账、重启幂等、续费、到期保留文件和停用后的旧回调。原生测试独立APK验证价格、二维码解码、被拦截的付款启动Intent、服务端到账推送、续费标识、账号切换和监听器清理，不打开真实付款页、不发起真实支付。
