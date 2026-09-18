# 轨迹跑 (TrailRun)

一个用于**定位功能开发调试 / 地图测试 / 隐私保护**的 Android 虚拟定位工具。
支持在浅色地图上**像画图一样手绘轨迹**，也可点选起终点；设定速度后一键回放，
支持环线刷圈、目标距离、轨迹抖动与路线预设。

> ⚠️ **使用须知**
> 本工具用于定位相关功能的开发调试、地图与导航应用测试、以及不希望在运动记录中暴露真实住址的隐私保护场景。
> 部分校园跑 / 运动打卡类应用会检测模拟位置、异常配速与加速度，可能判定成绩无效。是否使用请自行判断，
> 并遵守你所在学校与平台的规定。

---

## 下载

最新版 **v1.0.0** · 17.6 MB · Android 8.0+ · 无需 Root

| 渠道 | 链接 | 说明 |
| --- | --- | --- |
| **jsDelivr CDN** | [`TrailRun-1.0.0.apk`](https://cdn.jsdelivr.net/gh/wangwangrr/TrailRun@main/release/TrailRun-1.0.0.apk) | 有国内节点，通常最快 —— **推荐国内用户用这个** |
| GitHub Release | [最新 Release](https://github.com/wangwangrr/TrailRun/releases/latest) | 官方渠道；下载走 `github.com`，国内可能连不上 |
| 仓库内直链 | [`raw`](https://raw.githubusercontent.com/wangwangrr/TrailRun/main/release/TrailRun-1.0.0.apk) | 备用 |

装好后按下面「二、在手机上使用」的 4 步完成设置。

> 为什么同一个 APK 放三个地方：GitHub Release 的下载链接会 302 到
> `objects.githubusercontent.com`，而 `github.com` 本身在国内经常不可达 ——
> 实测本机环境就是 `github.com` 超时、而其余 GitHub 域名正常。
> 把 APK 在仓库里也存一份，就能借道 jsDelivr（国内有节点）和 raw 直链。
> 代价是仓库多了 17.6 MB，且每次更新 APK 都会在 git 历史里留一份。
> 另外 jsDelivr 对单文件有 **20 MB** 限制，APK 再大就得换别的方式。

---

## 一、功能一览

| 能力 | 说明 |
| --- | --- |
| **手绘轨迹** | 切到「手绘轨迹」模式后**按住屏幕拖动，像画图一样把轨迹画出来**，松手即成路线；可分成几笔接着画。落笔会自动抽稀（Douglas-Peucker），几百个采样点压成十几个拐点 |
| 点选设点 | 「点选」模式下点地图即加点，长按也可以；点圆点删除 |
| 精确落点 | 点选模式下的准星按钮：拖动地图对准目标，点「在此设点」精确落点，实时显示中心经纬度 |
| 定位到我 | 右上角定位按钮，一键缩放到当前位置（街道级）；首次打开地图也会自动定位到你所在位置 |
| 自定义速度 | 预设 6 / 8 / 10 / 12 / 15 km/h，滑杆可在 2–25 km/h 微调，实时显示配速 |
| 路线方式 | 单程（到终点结束）／环线刷圈／折返（到终点自动掉头反复跑） |
| 目标距离 | 设定 1–5 km，达到后自动停止 |
| 轨迹抖动 | 可开启的微小随机偏移，避免轨迹呈现完美直线 |
| 路线预设 | 保存 / 载入 / 重命名 / 删除，支持 JSON 导入导出，多套路线一键切换 |
| 后台运行 | 前台服务 + 常驻通知，息屏或切到其他应用仍继续 |
| **地点搜索** | 标题栏下方是**搜索框**（不是角落小图标，好点也好认）：输入地名（支持中文、拼音），选中结果即可移动地图或一键加为坐标点。用 Photon（Komoot 开源的 OSM 地理编码），实测国内可直连、**无需任何 API Key** |
| **按坐标输入** | 地图右侧图钉按钮：直接填经纬度添加一个点，适合把起点定死在精确位置 |
| **整笔撤销** | 手绘一笔会写入几十个点，撤销栈按「操作快照」记录，一笔 / 一次清空 / 一次载入都能一步撤销（最多 40 步） |
| **进入口令** | 启动页之后要求输入一次口令。界面给出提示「作者的名字缩写」，**不区分大小写**（也容错全角字母与误打的空格）。输对一次即写入本机，之后冷启动、杀进程、重启手机都不再询问；卸载重装或清除应用数据会重新要求输入 |
| 底图 | 只用 **OpenStreetMap**。默认使用返回 **512×512** 的高清镜像 —— 同样的视野和字号，像素密度是 256px 瓦片的两倍；也可以在「底图与离线」里手动换成别的镜像 |
| **连接诊断** | 「更多 → 底图与离线设置」里点「测速并选最快」：手机会逐个端点请求一张真实瓦片，显示 HTTP 状态、耗时、**服务端真实返回的像素尺寸**，并自动切到最好用的那个 |
| **离线可用** | 切到「离线模式（不联网）」后完全切断网络请求，只读本地缓存；也可导入 .mbtiles / .sqlite / .zip 离线包 |
| 界面风格 | 小清新浅色系：薄荷绿主色 + 天蓝辅助色，浅底白卡、大圆角、极浅描边 |
| 应用图标 | 自适应图标（含 Android 13+ 单色主题图标）：浅薄荷底 + 一笔墨绿轨迹 + 蜜桃色终点 |

### 进入口令

冷启动的顺序是：**启动页 →（首次才出现）口令页 → 主界面**。

口令写在 `core/AppPassword.kt` 里，比较前会先归一化：全角转半角 → 去掉所有空白 → 转小写。
这几步不是过度设计，针对的都是手机上真实会发生的情况：中文输入法打出来的全角字母
（ｗｊｗ）、全角空格、以及「明明输对了却进不去」的挫败感。

> **它是什么，不是什么**：这是挡住「别人拿起手机随手点开」的**软锁，不是安全机制**。
> 口令以明文写在代码里，反编译 APK 就能看到；设备上只存一个「已解锁」的布尔值，
> 清除应用数据就会重置。之所以不把它藏起来（拆字符、异或、塞进 assets），
> 是因为那些做法只能骗过 `strings` 一类的粗略搜索，却会让这段代码看起来
> 提供了它并不提供的安全性 —— 那比明说更危险。

持久化只存布尔值、不存口令本身：存了口令反而多出一份可以被拷走的秘密，
而这里要的只是「这台设备问过一次就不再问」（`RouteRepository.unlocked`）。

对应地，`tools/verify/PasswordTest.kt` 会断言大小写、空格、全角字母全部能通过，
同时保证空输入、少一个字符、多一个字符、以及提示语本身都不能通过。

### 底图说明：只用 OpenStreetMap

底图只保留 OSM 一个来源，但可以从不同镜像站取。**同一时刻只用其中一个**，
默认是返回 512×512 瓦片的那个高清源。

| 优先级 | OSM 端点 | 实测（`tools/probe-retina*.mjs`） |
| --- | --- | --- |
| 1 | `osm.rrze.fau.de/osmhd` | 可用，**512×512**，最快（热连接约 240ms） |
| 2 | `a.tile.openstreetmap.fr/hot` | 可用，256×256，约 1.1s |
| 3 | `tile.openstreetmap.de` | 可用，256×256，约 1.2s |
| 4 | `tile.openstreetmap.jp` | 可用，256×256，约 1.4s |
| 5 | `tile.openstreetmap.org`（官方） | **完全不可达**（多次取样全部超时） |
| — | `basemaps.cartocdn.com`（Carto） | **完全不可达**（全部 12s 超时） |
| — | `a.tile.openstreetmap.fr/osmfr` | 可用但极慢（5.7s），已从列表移除 |

**为什么用 512px 的源**：这是「地图糊」的正解，不是换滤镜能解决的。
osmdroid 在 `setTilesScaledToDpi(true)` 下，屏幕上的绘制尺寸恒为
`256 × 屏幕密度`，**与源分辨率无关**（见 `MapView.updateTileSizeForDensity`：

```
density = displayDensity * 256 / tileSize
size    = tileSize * density        // 恒等于 256 * displayDensity
```

所以 256px 的源被放大到 `displayDensity` 倍（典型 2.6 倍）→ 糊；
512px 的源只被放大 `displayDensity / 2` 倍（约 1.3 倍）→ **清晰一倍**，
而视野范围、字号完全一致。换 512px 源是纯赚，没有取舍。

> 缺点也要说清楚：OSM 数据在国内的**中文注记覆盖不如高德**，
> 小地名可能搜不到、部分道路没有中文名。它换来判断依据透明、无 Key、无商用授权问题。
> 若确实需要中文街道图，需要自行接入有授权的地图服务（那需要申请 Key）。

**坐标系说明**：OSM 使用 WGS-84，与 GPS 一致，**不存在高德那种 GCJ-02 偏移问题**，
所以地图上的位置与模拟出来的坐标是对得上的。

### 界面布局

设计原则：**主色只用于可交互元素**；地图区域内不放任何横跨元素；同类控件合并成一行。

```
┌──────────────────────────────────────┐
│ 轨迹跑   ● 就绪                  (🔍) │  顶部栏：标题 + 状态 + 搜索
│ ┌──────────────┐          ┌───┐      │
│ │ 点选 │ 手绘  │          │撤销│      │  左上：模式切换
│ └──────────────┘          │定位│      │  右上：3 个地图工具
│                           │更多│      │
│                           └───┘      │
│                                      │
│                    © OpenStreetMap   │  左下：署名
├──────────────────────────────────────┤
│ 轨迹点12个 单程350m 预计2m30s 3.00km │  路线结构
│ ──────────────────────────────       │  运行中才出现
│ 9.0 km/h    ═══●═════    6 8 10 12 15│  速度：数字+滑杆+预设 同一行
│ 配速 6'40"                           │
│ 路线  单程 │ 刷圈 │ 折返              │  路线方式：一行
│ ⌄ 高级选项                           │  折叠入口
│ ┌──────────────────────────────────┐ │
│ │            开始模拟              │ │  主操作
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

改动要点（针对「搜索框位置丑、按钮排布紊乱」的反馈）：

| 项 | 之前 | 现在 |
| --- | --- | --- |
| 搜索 | 地图上横跨全宽的搜索条 | 顶部栏右侧 40dp 圆形图标按钮 |
| 地图工具 | 右侧 5 个圆按钮竖排 | 3 个（撤销 / 定位 / 更多），其余收进折叠菜单 |
| 速度控制 | 标题行 + 预设行 + 滑杆行（3 行） | 数字 + 滑杆 + 预设（1 行） |
| 路线方式 | 标题行 + chip 行（2 行） | 前缀标签 + chip（1 行） |
| 统计行 | 4 格，含与速度控制重复的「设定速度」 | 只讲路线结构（点数 / 单程 / 用时 / 计划总程） |
| 高级选项入口 | `TextButton`（自带 48dp 最小高度） | 紧凑整行可点区域，右侧摘出关键设置 |
| 主按钮 | 52dp | 48dp |

> 底部面板之前是「统计 + 进度条 + 速度标题 + 预设 + 滑杆 + 模式标题 + chip +
> 高级选项 + 主按钮」共 9 段纵向堆叠，占了近半屏；现在合并为 4 段可视区块。
>
> 搜索从地图上移走的另一个好处：地图区域内不再有横跨元素，
> 也就不会再出现「搜索框被地图盖住、拖动地图才闪现」这类渲染层级问题。

### 技术要点

- **无需 Root**：使用系统公开的 `LocationManager.addTestProvider / setTestProviderLocation`
  接口写入位置，需在「开发者选项 → 选择模拟位置信息应用」中选中本应用。
- **不做注入**：不修改、不 hook、不注入任何其他应用，只提供一个系统认可的 GPS provider。
- **轨迹精度**：位置严格按 `设定速度 × 真实经过时间` 沿折线推进，推送频率 5 Hz，
  因此调用间隔抖动不会造成速度偏差。
- **手绘抽稀**：手指拖动会采样出成百上千个点，先按 2m 最小间距粗过滤，
  再用 Douglas-Peucker 以 6m 容差抽稀，只保留决定形状的拐点。
- **距离计算**：WGS-84 近似球体公式（haversine），跑步尺度上误差在米级以内。

---

## 二、在手机上使用（4 步）

1. **开启开发者选项**：设置 → 关于手机 → 连续点击「版本号」7 次。
2. **选择模拟位置应用**：设置 → 系统 → 开发者选项 → **选择模拟位置信息应用** → **轨迹跑**。
   （部分国产 ROM 该入口叫「模拟位置信息应用」或藏在「开发者选项 → 调试」里）
3. **关闭电池优化**：设置 → 应用 → 轨迹跑 → 耗电管理 → 允许后台高耗电 / 无限制。
   同时允许「位置信息」与「通知」权限。
4. **画路线开跑**：在「模拟」页点「手绘轨迹」，按住屏幕把路线画出来 → 调速度 → 点 **开始模拟**。

应用内「说明」页有同样的步骤，并带「打开开发者选项」「重新检测」按钮。
顶部状态胶囊显示 `就绪` 表示已获得模拟位置权限。

---

## 三、编译出 APK

### 当前状态：本机环境已装好，APK 已编译完成

```
D:\app\Android\TrailRun-debug.apk                            <- 直接拷到手机安装
D:\dsh\TrailRun\app\build\outputs\apk\debug\app-debug.apk     <- 构建原始产物
```

已就位的工具链（全部装在 D 盘）：

| 组件 | 路径 | 说明 |
| --- | --- | --- |
| Android Studio | `D:\app\Android\Android Studio` | 2024.1.2.12，**便携解压版**（安装器需要管理员权限，改为解压释放，免 UAC） |
| JDK 17 | `D:\app\Android\Android Studio\jbr` | Studio 自带，已设为用户环境变量 `JAVA_HOME` |
| Android SDK | `D:\app\Android\Sdk` | platform-tools / platforms;android-34 / build-tools;34.0.0 |
| Gradle 8.7 | `D:\dsh\TrailRun\.toolchain\gradle-8.7` | 从腾讯云镜像获取 |
| Gradle Wrapper | `D:\dsh\TrailRun\gradlew.bat` | 已生成，`distributionUrl` 指向腾讯云镜像 |

已写入的用户环境变量：`JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`。

### 重新编译（三种方式）

```powershell
# 方式 1：一键脚本（推荐，自动处理环境变量与镜像）
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1

# 方式 2：直接用 wrapper（新开终端让环境变量生效）
cd D:\dsh\TrailRun
.\gradlew.bat assembleDebug

# 方式 3：打开 Android Studio -> File > Open 选 TrailRun 目录 -> Build > Build APK(s)
```

### 安装到手机

```powershell
# 手机开 USB 调试并连上电脑
D:\app\Android\Sdk\platform-tools\adb.exe install -r D:\app\Android\TrailRun-debug.apk
```

或直接把 `D:\app\Android\TrailRun-debug.apk` 传到手机点击安装（需允许「安装未知来源应用」）。
APK 已用 debug 证书签名（v2 方案，`apksigner verify` 通过），可直接安装。

> 若从旧版本升级：因为权限清单里新增了 `ACCESS_MOCK_LOCATION`，
> 建议先卸载旧版再装新版，或用 `adb install -r` 覆盖安装。

### 本机网络环境的坑（已解决，供以后参考）

1. **`services.gradle.org` 与 GitHub Releases 不可达** —— Gradle 发行版和 `gradle-wrapper.jar` 都拿不到。
   解决办法：改用腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/`，
   并用 `gradle wrapper --gradle-distribution-url <镜像地址>` 生成 wrapper
   （`--gradle-version` 会去联网校验官方地址，因此必须显式指定镜像 URL）。
2. **PowerShell 的 HTTPS 全部失败**（`SEC_E_NO_CREDENTIALS`）—— 但 Node 的 OpenSSL 栈正常。
   解决办法：下载统一走 `tools\dl.mjs`（Node 实现，支持重定向与进度显示）。
   JDK 自带 `cacerts`，所以 sdkmanager 与 Gradle 能正常联网，这一点在 `bootstrap-android.ps1`
   里做成了「TLS 预检」，先验证再往下走。
3. **Android Studio 2024.1 不再自带可运行的 Gradle** —— `plugins\gradle\lib` 里只有
   `gradle-api-*.jar`，没有 `gradle-launcher-*.jar`，所以必须单独准备 Gradle 发行版。
4. **PowerShell 5.1 用 GBK 读无 BOM 的 UTF-8 脚本** —— 中文会变乱码，某些字节序列甚至会被
   解码成引号从而破坏语法。所以 `tools\` 下的脚本一律写成**纯 ASCII**。
   同理不要用 `Get-Content -Raw` 读写含中文的文本文件，会双重编码毁掉内容；
   必须用 `[System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)`。

另外 `D:\app` 不在 DSH 沙箱的可写范围内，`tools\` 下的脚本把整套流程收成一次授权即可完成。

### 从零开始（换一台机器时）

```powershell
# 1) 下载安装包（Node 实现，避开 schannel 限制）
node tools\dl.mjs "https://dl.google.com/dl/android/studio/install/2024.1.2.12/android-studio-2024.1.2.12-windows.exe" ".toolchain\android-studio.exe"
node tools\dl.mjs "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" ".toolchain\cmdline-tools.zip"
node tools\dl.mjs "https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip" ".toolchain\gradle-8.7-bin.zip"

# 2) 释放 Studio + 装 SDK（需要 7-Zip；会写 D:\app，需相应权限）
powershell -ExecutionPolicy Bypass -File tools\bootstrap-android.ps1

# 3) 编译
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

---

## 四、工程结构

```
TrailRun/
├── settings.gradle.kts            仓库与模块声明
├── build.gradle.kts               插件版本（AGP 8.5.2 / Kotlin 1.9.24）
├── gradle.properties
├── gradle/wrapper/                Gradle Wrapper（distributionUrl 指向腾讯云镜像）
├── .github/workflows/build-apk.yml   云端出包
├── tools/
│   ├── dl.mjs                     Node 下载器（绕开 schannel 不可用）
│   ├── bootstrap-android.ps1      从零安装 Studio + SDK
│   ├── build-apk.ps1              一键编译
│   ├── verify-algorithms.ps1      算法自检（纯 JVM，不需要手机）
│   ├── audit_crash_risks.py       崩溃风险静态扫描
│   ├── cut_function.py            按括号深度切除 Kotlin 函数（行号法不可靠）
│   ├── cut_block.py               按括号配对切除代码块
│   ├── check_braces.py            括号配对自检
│   ├── check_resources.py         资源引用交叉检查
│   ├── probe-osm.mjs              OSM 端点可达性实测
│   ├── probe-search.mjs           地点搜索服务可达性实测
│   ├── probe-tiles2.mjs           瓦片请求头对照实验
│   └── verify/                    算法断言源码（纯 JVM）
│       ├── PathSimplifyTest.kt      抽稀算法
│       ├── RouteSimulatorTest.kt    回放状态机
│       ├── PlaceSearchTest.kt       搜索解析（含真实联网实搜）
│       └── TileProviderTest.kt      镜像选择与排序
└── app/src/main/
    ├── AndroidManifest.xml        权限、前台服务、通知 Receiver
    ├── java/com/trailrun/mockgps/
    │   ├── MainActivity.kt            入口、权限申请、跳开发者选项
    │   ├── TrailRunApp.kt             Application：崩溃日志安装 + osmdroid 初始化
    │   ├── core/RouteSimulator.kt     轨迹几何 + 回放状态机（核心）
    │   ├── core/PathSimplify.kt       手绘抽稀（Douglas-Peucker）
    │   ├── core/OsmEndpoints.kt       OSM 端点定义与镜像排序（不依赖 osmdroid，可测）
    │   ├── core/TileProvider.kt       把端点接到 osmdroid 的瓦片源
    │   ├── core/TileDiagnostics.kt    逐端点连通性诊断
    │   ├── core/PlaceSearch.kt        地点搜索（Photon，免 Key）
    │   ├── core/LocationHelper.kt     取当前位置（含超时与缓存回退）
    │   ├── core/OfflineTiles.kt       离线包识别与缓存体积
    │   ├── core/CrashLog.kt           未捕获异常堆栈记录
    │   ├── data/RoutePreset.kt        路线预设模型与 JSON 序列化
    │   ├── data/RouteRepository.kt    SharedPreferences 持久化
    │   ├── service/MockLocationEngine.kt   test provider 挂载与位置注入
    │   ├── service/MockLocationService.kt  前台服务，5Hz 推送
    │   ├── service/LiveState.kt       服务 -> 界面的状态通道
    │   ├── service/ServiceStarter.kt  前台服务启动兜底
    │   └── ui/
    │       ├── RootScreen.kt          底部三页导航
    │       ├── MainRunScreen.kt       主界面：地图 + 模式切换 + 速度控制
    │       ├── RoutesScreen.kt        预设管理、导入导出
    │       ├── GuideScreen.kt         使用步骤、注意事项、崩溃日志
    │       ├── Common.kt              共用小组件
    │       ├── MainViewModel.kt       全部交互逻辑
    │       ├── components/RouteMapView.kt   osmdroid 封装 + 手绘 + 镜像切换
    │       └── theme/Theme.kt         小清新浅色配色
    └── res/                       图标、字符串、主题
```

---

## 五、算法自检

纯 Kotlin 的几何与回放逻辑可以脱离 Android 直接在电脑上验证，不需要手机或模拟器：

```powershell
powershell -ExecutionPolicy Bypass -File tools\verify-algorithms.ps1
```

它会用 Gradle 缓存里的 `kotlin-compiler-embeddable` 编译 `core/` 下无 Android 依赖的组件，
再跑 `tools/verify/` 里的断言。当前覆盖：

- **PathSimplifyTest** — 400m 操场椭圆抽稀后点位压缩到 9 个、最大偏离 4.03m（容差 6m）、
  弧长误差小于 8%；200 点抖动轨迹被抹平到 2 点；退化输入（空/单点/全重合）不崩溃。
- **RouteSimulatorTest** — 10 km/h 推进 60 秒的位移误差小于 3m；单程模式到终点停止且速度归零；
  环线模式 2500m 时正好记 1 趟、4100m 时记 2 趟；10 秒 delta 被截断到 5 秒防瞬移；
  抖动幅度不超设定值；非法参数抛异常。
- **PlaceSearchTest** — 用**真实抓取的 Photon 响应**验证解析（不是手写一份「我以为的」JSON，
  那样只能验证自己和自己一致）；覆盖缺 geometry / 缺 coordinates / 坐标是字符串 /
  缺 name 时回退到 street、city；校验 URL 编码、`limit`、以及**不能带 `lang` 参数**
  （实测会返回 HTTP 400）；最后做一次**真实联网搜索**，网络不通时自动跳过不算失败。
- **TileProviderTest** — 镜像选择的纯逻辑：自动模式保持内置顺序；**指定任一端点后必须排到第一位**，
  且其余端点不丢、不重复、相对顺序不变；非法 URL 还原为「自动」；端点列表的基本约束
  （URL 唯一、https 开头、label 唯一）。
  这段逻辑之所以抽成不依赖 osmdroid 的独立文件，就是为了能在电脑上断言 ——
  否则「用户选了镜像却没生效」只能等装到手机上才发现。

这套自检抓到过两个真实 bug：折返时 `legIndex` 被设成 `lastLegIndex` 而非
`lastLegIndex - 1`（段下标与点下标差一），导致**整趟被吞掉、趟数永不增长**；
以及离线模式下点选其他瓦片源时选择被静默吞掉。

### 第二轮代码审计（修掉的问题）

这一轮专门查「前几轮改动自己引入的新问题」，找到 6 个真问题：

| 问题 | 影响 | 修法 |
| --- | --- | --- |
| `consumeFocusTarget()` 定义了却从未调用 | `focusTarget` 残留，Effect 一旦重启就把地图**弹回上次搜索点**，用户拖走也没用 | 聚焦后立刻消费；并加递增令牌，解决「连续搜同一地点坐标不变 → StateFlow 不发射 → 地图不动」 |
| 只有 4 处会保存草稿，**点选/手绘加的点不落盘** | 强杀应用后路线全丢 | 保存挂到所有状态变更上，500ms 防抖合并（手绘每点都触发 update，不能每点写盘） |
| `renderRoute()` 在每次重组无条件执行 | 运行时 5Hz 刷新 → 每秒重建 5 次全部标记与折线；手绘时几乎每帧重建，白白掉帧 | 用「点数 + 全点哈希 + 首末点」签名做守卫；签名必须覆盖全部点，只看首尾时中间点变化不会重绘 |
| `LocationHelper` 的超时消息从不取消 | 每次点定位泄漏一条持有 Context 的 Handler 消息（最长 6 秒），反复点会堆积 | 保存 Runnable 引用，拿到结果时 `removeCallbacks` |
| 草稿漏存**目标距离** | 设了「跑够 3km 自动停」，冷启动后变回「不限」，模拟会一直跑下去 | 草稿补上 `target` 字段，存取对称 |
| 5 处死代码（含一个只在删完之后才暴露的 `offlineNetworkCheck`） | 混淆视听；删错时才发现紧邻声明也被带走 | 删除；`isEmptyResult` 改为真正用上——搜索无结果时给出替代建议而不是干瞪眼 |

> 审计过程本身也踩了坑：我用 PowerShell 按行号删除方法体，结果**误删了紧邻的
> `offlineNetworkCheck` 属性、`setTileProvider()` 方法**，编译才发现。按行号做外科手术
> 不可靠，删完必须立刻编译验证。

---

### 第三轮：地图「不清晰 + 太卡」（修掉的问题）

用户反馈只有一句：「地图用起来不清晰，而且太卡了」。
读了 osmdroid 6.1.18 的源码（`sources.jar` 从 Maven Central 拉下来，见 `.toolchain/osmdroid-src/`）
之后，找到 7 个原因，其中第 1 条是决定性的：

| # | 问题 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | **把 4 个 baseUrl 一起传给了 osmdroid** | `OnlineTileSourceBase.getBaseUrl()` 是 `mBaseUrls[random.nextInt(len)]` —— **随机挑一个，而且没有任何失败重试**。4 个端点里有 1 个完全不可达、1 个要 5.7s，等于每块瓦片都有过半概率发往坏域名。下载线程被超时请求占死、瓦片一块块地空、缺席的瓦片只能拿上级低清瓦片放大顶着画 → **又空又糊又慢** | `resolveEndpoint()` 只解析出**唯一**一个端点；`RecordingTileSource` 重写 `getBaseUrl()` 固定返回它 |
| 2 | 底图是 256px 的源，被 osmdroid 放大 2.6 倍绘制 | 结构性发虚 | 默认换成返回 **512×512** 的 `osm.rrze.fau.de/osmhd`；放大倍率降到 1.3 倍 |
| 3 | 换源只调用 `tileProvider.setTileSource()` | 它**不会**调用 `updateTileSizeForDensity()`，`TileSystem` 的边长仍是 256。从 256 端点切到 512 端点时，512 的图被硬塞进 256 的格子，比不换还糊 | 改调 `map.setTileSource()`（内部会同步 TileSystem） |
| 4 | `renderRoute()` **给每个点建一个 Marker** | 手绘几百点的轨迹 = 几百个覆盖物，每个每帧都要 save/setBounds/draw 一个 BitmapDrawable → 每帧十几毫秒，平移必掉帧，还多占几 MB 位图 | 超过 40 点只保留起终点 Marker；圆点图标按 (颜色,半径) 缓存，不再每点新建 Bitmap |
| 5 | 跟随模式用 `animateTo()` | 位置每秒更新 5 次，每次都重启滚动动画：动画永远播不完，而且动画每一帧都回调 `onScroll` → 5Hz 被放大成 60Hz 的反馈链，地图一直在抖 | 跟随改用 `setCenter()` |
| 6 | 地图中心每次 `onScroll` 都写 Compose 状态 | 拖动时手指每移动一次就 `scrollBy` → `onScroll`（最高 120Hz）→ **每秒上百次整屏重组**，每次又重跑 `AndroidView.update` → 重画标记 → 再重绘地图 | 回写节流到 200ms；落点 / 搜索取景改为按下那一刻从 controller 读实时值，精度不受影响 |
| 7 | 手绘预览每帧新建 ArrayList + N 个 PointF，再用 `drawLine` 画 2N 条线 | 一笔 300 点就是每帧 300 次分配 + 600 次绘制调用 → 掉帧 + GC 抖动 | 复用同一个 `Path`，整条笔画只画两次（光晕 + 主线）；顺带去掉十字准星每帧的 `listOf()` |

另外三处小修：
- `「清新」配色滤镜带 +12 的提亮偏移`，把黑色道路描边和文字一起抬成灰的 → 对比度下降，
  本身就在制造「不清晰」。改成只做轻微降饱和、黑场不动，默认配色也改回「标准」。
- `setMapStyle()` 无条件 `setColorFilter`：它在每次重组时都会被调用，运行时每秒 5 次，
  每次都让瓦片层失效重绘。加变化守卫。
- osmdroid 默认只有 **2 个下载线程**（`tileDownloadThreads=2`）：一屏 8~20 块瓦片排队，
  表现为「地图一块一块慢慢往外冒」。调到 4；读缓存从 8 降到 4（底下只有一个 SQLite 连接，
  线程多了反而互相等锁）。

> 这一轮最值得记的一条：**「多个镜像自动容错」这个设计本身是错的**。
> 直觉上「多给几个地址总有一个能通」，但 osmdroid 的实现是随机选一个、且不做失败切换，
> 于是「容错」实际变成了「把大部分请求丢给坏地址」。查库源码比看文档和猜都快 ——
> `sources.jar` 就在 Maven Central 上，拉下来 30 秒的事。

---

### 崩溃防护与日志

真机闪退过一次（「一进就闪退」），原因是 `MockLocationEngine.canMock()` 的 catch 列表过窄：
它只捕 `SecurityException` / `IllegalArgumentException` / `UnsupportedOperationException`，
而某些 ROM 的 `ProviderProperties.Builder().build()` 会抛 `IllegalStateException`。
这个调用发生在 **ViewModel 构造期间**、跑在 `viewModelScope` 协程里，
未捕获异常会直接崩掉主线程 —— 表现为「点开就闪退」。

修复思路不是打补丁，而是**把整条启动路径改成不可能因单点故障而打不开**：

| 位置 | 处理 |
| --- | --- |
| `MockLocationEngine.canMock()` | 末尾统一 `catch (e: Throwable)`，检测失败按「未授权」处理 |
| `MainViewModel.init` | 整体 try/catch，恢复失败就按默认值启动 |
| `refreshMockPermission` | 协程内整体 try/catch |
| `schedulePersist` / `runSearch` | 协程内 try/catch（这两个协程在每次状态变更时都会启动，崩溃面最大） |
| `LocationHelper.requestCurrent` | 补 `catch (e: Throwable)` |
| `TrailRunApp.onCreate` | osmdroid 初始化包 `runCatching` |
| `MainActivity.onCreate` | `enableEdgeToEdge` / 权限申请分别包住 |
| `MapTileProviderBasic` 的 `IRegisterReceiver` | 传 `SimpleRegisterReceiver` 而不是 `null`（传 null 依赖库内部空值处理，某个版本改成直接使用就会 NPE） |
| `EditorState` 的数值字段 | 在**数据入口**（读盘）与**界面取值处**（Slider 前）各清洗一次 |

> 关于数值清洗：Compose 的 `Slider` 在 `value` 为 NaN 或无穷时会直接抛
> `IllegalArgumentException`（"value must be finite"），而滑块是常驻界面元素，
> 偏好里一旦存过脏数据就会启动即闪退。清洗放在入口而不是用 `require` 断言 ——
> 断言只会把「数据脏」变成「应用打不开」。

**崩溃日志**：`Application` 里安装了 `Thread.setDefaultUncaughtExceptionHandler`，
未捕获异常的完整堆栈会写到应用私有目录（含时间、线程、Android 版本、机型）。
在「说明」页底部可以直接**查看 / 复制 / 清除**。
再次闪退时，把复制的日志发来即可精确定位，不必再靠推测。

另外加了 `tools/audit_crash_risks.py`：静态扫描所有协程入口、`init` 块、
生命周期回调，以及「catch 列表过窄」的位置，防止以后新增代码重新引入同类问题。

---

## 六、关于地图交互的一个重要坑（osmdroid 6.1.18）

**症状**：地图能拖动、能缩放，但点击地图没有任何反应，加不了点。

**原因**（通过反汇编 `osmdroid-android-6.1.18.aar` 的 `classes.jar` 确认）：

- `MapView` 内部用 `MapViewGestureDetectorListener` 处理手势，它实现了
  `onDown / onFling / onLongPress / onScroll / onShowPress / onSingleTapUp`，
  **唯独没有实现 `onSingleTapConfirmed`**；
- 因此 `OverlayManager.onSingleTapConfirmed` 永远不会被调用；
- 而 osmdroid 官方自带的 `MapEventsOverlay` **只重写了 `onSingleTapConfirmed` 和 `onLongPress`**，
  没有重写 `onTouchEvent` / `onSingleTapUp` —— 于是它在 6.1.18 上完全收不到点击。

**正确做法**：自定义一个 `Overlay`，重写 **`onSingleTapUp`**：

```kotlin
class TapOverlay(private val onTap: (Double, Double) -> Unit) : Overlay() {
    override fun onSingleTapUp(e: MotionEvent, mapView: MapView): Boolean {
        val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) ?: return false
        onTap(p.latitude, p.longitude)
        return true
    }
    // 不要重写 onTouchEvent，让它返回 false，否则会挡住地图拖动
}
```

另外三个签名陷阱：

- `Overlay.draw` 是**三参数** `(Canvas, MapView, Boolean)`，不是两参数；
- 自定义在线瓦片源要用具体的 `XYTileSource`，`OnlineTileSourceBase` 是抽象类，无法实例化；
- 手绘模式需要拦截触摸，做法是继承 `MapView` 并重写 `dispatchTouchEvent`，
  在手绘开启时把事件全部消费掉（地图不跟着平移），关闭时完全交给父类。

---

## 七、常见问题

**开发者选项里找不到「轨迹跑」这个应用？**
必须在 `AndroidManifest.xml` 里声明 `android.permission.ACCESS_MOCK_LOCATION`。
系统的「选择模拟位置信息应用」列表是按这个权限过滤候选应用的，缺了它应用根本不会出现在列表里
（该权限在 API 23 起被标记为 deprecated，但**仍然必须声明**）。

**开始后其他应用读到的还是真实位置？**
说明本应用还没被选为模拟位置应用。到开发者选项里选中它，然后回到「说明」页点「重新检测」，
顶部胶囊变成 `就绪` 再开始。部分 ROM 需要先关闭再重新打开一次目标应用。

**应用闪退 / 打不开？**
「说明」页底部有**崩溃日志**入口（出现过闪退时才会显示），
点「复制」把完整堆栈发来即可精确定位 —— 里面包含时间、线程、Android 版本、
机型和完整堆栈，比描述现象有用得多。

也可以直接用 adb 抓：
```powershell
D:\app\Android\Sdk\platform-tools\adb.exe logcat -b crash -d
```

**地图一片空白 / 一直转圈？**
先看**镜像**。图层按钮里有「底图镜像」，默认「自动」会按内置顺序依次尝试，
你也可以**手动锁定一个能通的**：

| 顺序 | OSM 端点 | 实测结果 |
| --- | --- | --- |
| 1 | `a.tile.openstreetmap.fr/hot` | 可用，最快（788ms） |
| 2 | `a.tile.openstreetmap.fr/osmfr` | 可用（约 2.4s） |
| 3 | `tile.openstreetmap.de` | 可用（约 2.6s） |
| 4 | `tile.openstreetmap.org`（官方） | **完全不可达**（多次取样全部 9s 超时） |

同一个对话框里可以点**「测试连通性」**：手机会逐个请求这 4 个端点，
把 HTTP 状态、耗时或具体错误显示在每个镜像下方，并标注「可用 / 失败」。
照着结果手动锁一个即可。这一步能立刻区分三种情况：

- **有端点可用** → 手动锁定它，然后点云朵按钮重新加载瓦片；
- **全部失败且是 DNS 失败** → 域名被污染，换网络（切流量 / 换 Wi-Fi）；
- **全部失败且是连接重置或超时** → 当前网络出口被限制（校园网、企业网常见），
  此时请开启「离线模式」查看已缓存的区域。

> **手动指定镜像时，其余镜像仍作为后备**（只是排到后面）而不是被删掉。
> 这是刻意的：只留一个的话，那个镜像临时挂掉就整片空白，而用户并不知道原因；
> 保留后备则最多是慢一点，但依然能出图。

补充说明：偏好项已换成 `offline_map_v3` / `map_mirror`，启动时会自动清除历史版本遗留的
瓦片源配置，避免残留值把界面带到一个已不存在的源上。

**有离线地图吗？**
没有可以随包分发的离线底图——国内地图数据的离线包属于对应厂商的商用授权范围，
任何声称能免费提供的来源都不可靠，我也不会把这种东西打进包里。

但**离线本身是能用的**，本应用提供两条路：

1. **复用本地缓存（推荐，零配置）**：联网时把要去的地方浏览一遍（把关键区域缩放到位），
   瓦片会自动缓存；之后在图层对话框里开启「离线模式（不联网）」，
   应用会完全停止网络请求、只读缓存。对话框里会显示当前缓存体积。
2. **导入离线包**：把 `.mbtiles` / `.sqlite` / `.zip` / `.gemf` 放到
   `Android/data/com.trailrun.mockgps/files/offline/`，应用会自动识别。
   离线包来源请自行确保合法（例如用 OSM 数据自行制作，OSM 数据是 ODbL 授权）。

**定位不到我的位置？**
本版改成会**真正发起一次定位请求**（`getCurrentLocation`），而不是只读系统缓存
（`getLastKnownLocation` 在刚开机或长时间没定位时经常返回 null，这正是之前「定位不到」的原因）。
失败时会在界面上直接给出原因：没有定位权限 / 系统定位已关闭 / 暂时取不到位置。
另外定位权限刚授予的那一刻就会自动取一次位置并让地图聚焦过去。

**手绘画不出轨迹？**
确认已切到「手绘轨迹」模式（地图左上角的分段按钮）。该模式下**按住屏幕拖动**即可，
松手那一笔就变成路线。如果只是轻点一下不拖动，那一笔会太短而被忽略。

**模拟中途就自己停了？**
一是可能设了「目标距离」，达到后会自动停止，改成「不限」即可；
二是系统省电策略杀掉了前台服务，把本应用加入电池白名单。

**轨迹太规整，会不会被看出来？**
打开「高级选项 → 轨迹抖动」，会给每个点加米级随机偏移。
另外建议把速度设成正常跑步区间（8–12 km/h），配速恒定本身也是一种异常特征，这点本工具无法消除。

---

## 八、许可与免责

本工程供学习与自用调试。请勿用于伪造证明材料、代跑代打卡等作弊用途，
由此产生的一切后果由使用者自行承担。
