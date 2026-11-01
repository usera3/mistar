# 地图组件与服务

## Leaflet 1.9.4

本地文件位于`android/app/src/main/assets/location-map/`，使用BSD 2-Clause许可，完整许可文本见该目录的 [LICENSE](android/app/src/main/assets/location-map/LICENSE)。

来源为 [Leaflet官方下载页](https://leafletjs.com/download.html)列出的固定1.9.4版本；下载后按官方公开的SHA-256 SRI校验。JS：`20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=`；CSS：`p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=`。不在运行时加载远程脚本、插件或账号密钥。

## OpenStreetMap

地图上的OpenStreetMap版权标识和链接应保留。数据及底图服务的许可/使用范围见 [版权说明](https://www.openstreetmap.org/copyright)与[瓦片使用政策](https://operations.osmfoundation.org/policies/tiles/)。

客户端只经受限缓存通道请求用户当前查看的瓦片；客户端及缓存使用明确的应用User-Agent、HTTPS与HTTP缓存，不提供批量抓取、区域预加载或离线下载。缓存对外不记录瓦片访问日志，并限制格式、方法、连接数及频率，向上游清除身份/Cookie字段并验证TLS。缓存与底图方可了解所请求的地图区域和网络地址；请求不携带弥星的身份凭据或精确标记数据。网络可用性仍受缓存和底图服务影响。

缓存示例见`distribution/nginx-mistar-map-http.conf`及`nginx-mistar-map-server.conf`，需按主机配置可信DNS并创建受限缓存目录。自行部署必须修改`MapTiles`和地图HTML中的缓存域名。更换底图时同步修改来源/边界校验和版权标识，并遵守对应服务许可与隐私要求；不要扩大为任意URL代理或关闭TLS校验。

## MapLibre GL JS 5.24.0 与 OpenFreeMap

MapLibre CSP版JS/worker/CSS从固定NPM5.24.0包解包并验证registry的SHA-512 integrity；原始许可随App保存为 [MAPLIBRE-LICENSE.txt](android/app/src/main/assets/location-map/MAPLIBRE-LICENSE.txt)。不运行远程脚本。图形能力不足时使用已有Leaflet栅格渲染器。

矢量来源为 [OpenFreeMap](https://openfreemap.org/)，其官方说明允许商业使用、无需账号/Key。服务以尽力方式提供，不保证可用性；遵守 [服务条款](https://openfreemap.org/tos/) 和 [隐私政策](https://openfreemap.org/privacy/)，不提供区域离线下载或批量抓取。缓存节点及来源仍能了解被查看的区域，来源可能使用CDN/错误日志；聊天凭据与精确标记不随瓦片传送。

本地样式源自 [OpenFreeMap styles](https://github.com/hyperknot/openfreemap-styles) 的Liberty，调整了配色、中文优先、标签字号和来源地址。保留 [OPENFREEMAP-LICENSE.md](android/app/src/main/assets/location-map/OPENFREEMAP-LICENSE.md) 及 [LIBERTY-LICENSE.md](android/app/src/main/assets/location-map/LIBERTY-LICENSE.md)：样式代码BSD3及MIT，设计CC BY4.0；图标Maki为CC0，文本见 [MAKI-LICENSE.txt](android/app/src/main/assets/location-map/MAKI-LICENSE.txt)。本地Noto Sans Regular字形采用SIL OFL1.1，见 [NOTO-OFL.txt](android/app/src/main/assets/location-map/NOTO-OFL.txt)。地图保留OpenMapTiles与OpenStreetMap来源标识及链接；中文使用系统字体，不向在线字体服务发送位置名称。

矢量缓存示例为`nginx-mistar-vector-server.conf`与`nginx-mistar-vector-common.conf`，与已有受限栅格缓存同用128MiB上限。只允许GET/HEAD、固定元数据及版本/层级/瓦片路径，拒绝查询参数与任意URL，无瓦片访问日志；上游身份/Cookie清空。App另有24MiB矢量HTTP缓存、每个响应2MiB读取上限和路径/元数据校验；退出账号取消并清理地图缓存。
