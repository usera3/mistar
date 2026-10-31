# 地图组件与服务

## Leaflet 1.9.4

本地文件位于`android/app/src/main/assets/location-map/`，使用BSD 2-Clause许可，完整许可文本见该目录的 [LICENSE](android/app/src/main/assets/location-map/LICENSE)。

来源为 [Leaflet官方下载页](https://leafletjs.com/download.html)列出的固定1.9.4版本；下载后按官方公开的SHA-256 SRI校验。JS：`20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=`；CSS：`p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=`。不在运行时加载远程脚本、插件或账号密钥。

## OpenStreetMap

地图上的OpenStreetMap版权标识和链接应保留。数据及底图服务的许可/使用范围见 [版权说明](https://www.openstreetmap.org/copyright)与[瓦片使用政策](https://operations.osmfoundation.org/policies/tiles/)。

客户端只经受限缓存通道请求用户当前查看的瓦片；客户端及缓存使用明确的应用User-Agent、HTTPS与HTTP缓存，不提供批量抓取、区域预加载或离线下载。缓存对外不记录瓦片访问日志，并限制格式、方法、连接数及频率，向上游清除身份/Cookie字段并验证TLS。缓存与底图方可了解所请求的地图区域和网络地址；请求不携带弥星的身份凭据或精确标记数据。网络可用性仍受缓存和底图服务影响。

缓存示例见`distribution/nginx-mistar-map-http.conf`及`nginx-mistar-map-server.conf`，需按主机配置可信DNS并创建受限缓存目录。自行部署必须修改`MapTiles`和地图HTML中的缓存域名。更换底图时同步修改来源/边界校验和版权标识，并遵守对应服务许可与隐私要求；不要扩大为任意URL代理或关闭TLS校验。
