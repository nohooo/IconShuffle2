# 图标打乱 (IconShuffle)

通过 Shizuku 拿到系统级身份，直接读写桌面启动器的 `launcher.db`，
把 `favorites` 表里的图标坐标（`cellX` / `cellY`）随机重排，让每次找应用都像做一次舒尔特方格。

**目标设备**：红米 K80 至尊版 · HyperOS · Android 16 (API 36) · 桌面 `com.miui.home`

> ⚠️ **动手前请先读 [`docs/tech-notes.html`](docs/tech-notes.html)。**
> 你需求里「Shizuku 直读写 launcher.db 是唯一可靠路径」这个前提，
> 被你指定的参考项目自己的 README 推翻了 —— 他们把这条路径列为**未验证的 TODO**，
> 并实测出「读布局是 root 专属，shell/Shizuku ❌」。
> 本工程因此额外内置了一个**能力诊断**页面，用实测结果决定走哪条路，而不是靠猜。

---

## 一、如何编译

### 方式 A：Android Studio（推荐，最快）

1. Android Studio（Ladybug 2024.2 或更新）→ **Open** → 选中本目录 `IconShuffle/`
2. 首次打开会自动下载 Gradle 8.14.3 与依赖（需要能访问 google() 和 mavenCentral()）
3. 手机开 USB 调试并连上 → 右上角**绿色三角 Run**
4. 或者只出 APK：**Build → Build Bundle(s) / APK(s) → Build APK(s)**
   产物在 `app/build/outputs/apk/debug/app-debug.apk`

> 本工程没有提交 `gradlew` / `gradle-wrapper.jar`（二进制文件）。
> Android Studio 打开时会用 `gradle/wrapper/gradle-wrapper.properties` 自动补齐；
> 如果它没有自动补，在项目根执行一次 `gradle wrapper --gradle-version 8.14.3`，
> 或者直接在 Android Studio 的 Gradle 面板里点 Sync。

### 方式 B：GitHub Actions（不用装任何环境）

`.github/workflows/build.yml` 已配好：推到 GitHub 后自动跑单元测试 + 出 debug APK，
在 Actions 页面的 Artifacts 里下载 `icon-shuffle-debug`。

---

## 二、装好之后先做这三件事

1. 打开应用 → 点 **授权 Shizuku** → 弹窗选 **始终允许**
   （`Shizuku v13.6.0` 已装在机器上，但**本应用**需要单独授权）
2. 点 **能力诊断**，看最后的「结论」一节
3. 把那份报告发我 —— 它决定下一步是「继续走路径 ②」还是「换路径 ⑤」

### 诊断结论的四种情况

| 结论 | 含义 | 下一步 |
|---|---|---|
| ❌ 没找到 `launcher.db` | 桌面把布局存在别处 | 把报告里「桌面」小节的包名发我，我改探测列表 |
| ❌ 文件存在但读不到 | Shizuku 是 shell 身份，SELinux 拦住 `app_data_file` | 用 **root** 启动 Shizuku；否则要换路径 ⑤ / ① |
| ⚠️ 能读不能写 | `databases/` 目录属主是桌面 App | 同上 |
| ✅ 条件齐备 | 可以直接试 | 点「一键打乱」 |

---

## 三、界面上的三个控件（对应你的需求）

| 控件 | 说明 |
|---|---|
| **一键打乱** | 立即执行。完成后显示：实际交换了几个图标、涉及哪些页、备份文件在哪 |
| **打乱范围** | `打乱所有页面` = 每一页各自独立打乱，一次全部处理完<br>`仅当前页面` = 只打乱你正在用的那一页，其余页面一个图标都不动 |
| **每天自动打乱** | 打开后可选执行时间（默认 07:30）。用 WorkManager 周期任务，重启后自动恢复 |
| **保持 Dock 栏不变** | 默认开启。Dock 图标固定在 `container=-101`，不参与打乱 |

### 四条硬约束（任何模式下都成立，有单元测试钉死）

1. **总页数不变**
2. **每页占用的格数不变** —— 页内格子集合就是「谁占哪个格」的池子，打乱只是把图标
   重新指派到同一批格子上
3. **图标只在自己页面内部换位**
4. **绝不跨页迁移** —— 打乱前在哪个页面，打乱后还在哪个页面

算法层面这两条是**按构造保证**的（不是靠事后检查）：每个页面的图标被分成独立的一组，
目标坐标只从「本组当前占用的坐标」里取，因此跨页在数据结构上就不可能发生。
`ShuffleEngineTest` 用 3 页数据 + 多个随机种子逐条断言这四条不变式。

---

## 四、代码结构

```
app/src/main/
├── aidl/com/iconshuffle/shizuku/IPrivilegedService.aidl   # 特权侧跨进程接口
├── java/com/iconshuffle/
│   ├── IconShuffleApp.kt                # 注入包名、挂 Shizuku 监听、补调度
│   ├── shizuku/
│   │   ├── ShizukuGateway.kt            # 授权流程 + 用户服务绑定 + 三态状态机
│   │   └── PrivilegedService.kt         # 跑在 Shizuku 身份下的用户服务
│   ├── core/
│   │   ├── Model.kt                     # LauncherItem / CellMove / ShuffleScope
│   │   ├── LauncherDetector.kt          # 找默认桌面与 launcher.db 路径
│   │   ├── LauncherDb.kt                # 读 schema、读 favorites、事务写回、写后校验
│   │   ├── ShuffleEngine.kt             # 打乱算法（纯函数，可单测）
│   │   ├── LauncherDbChannel.kt         # 端到端编排：备份→取回→改→校验→原子替换写回
│   │   └── Prefs.kt                     # 设置持久化
│   ├── auto/
│   │   ├── DailyShuffleScheduler.kt     # 每日调度（对齐到用户设定时刻）
│   │   ├── DailyShuffleWorker.kt        # 执行体 + 结果通知
│   │   └── BootReceiver.kt              # 开机补调度
│   └── ui/
│       ├── MainActivity.kt              # 一键打乱 / 范围 / 自动开关
│       └── DiagnoseActivity.kt          # 能力诊断
└── res/                                 # 仅 XML 布局，无 Compose
```

### 几个刻意的设计决定

- **不硬编码 `favorites` 的列名**。先 `PRAGMA table_info(favorites)` 读真实 schema，
  再按实际存在的列组装读写语句。MIUI 的 `favorites` 表和 AOSP 不完全一样。
- **只 `UPDATE cellX / cellY`**。其余列、其余表一个字节都不碰。
- **不依赖系统 `sqlite3` 可执行文件**。把库复制到 App 自己的目录，
  用 `android.database.sqlite.SQLiteDatabase` 改写 —— HyperOS 上未必有 sqlite3。
- **写回走「临时文件 + 原子替换」**。`launcher.db` 是桌面 App 的 `0600` 文件，
  shell 直接覆盖必失败；所以 `cp` 到 `.new` → `chown` 回桌面 uid → `chmod 600` → `mv`。
- **默认不自动重启桌面**。`pkill` 能力已实现，但默认关闭，避免 HyperOS 上黑屏。

---

## 五、安全网

| 机制 | 说明 |
|---|---|
| 自动备份 | 每次打乱前复制出 `databases/launcher.db.bak-yyyyMMdd-HHmmss` |
| 可回滚 | `LauncherDbChannel.restore(backupPath, dbPath, launcherPackage)` 已实现，走同样的原子替换路径 |
| 写后校验 | 写完重新打开数据库逐条比对目标坐标，不匹配会明确报告 |
| 事务 | 全部换位在一个 SQLite 事务里提交，不会留半个状态 |
| 不动的东西 | widget、跨格图标、文件夹**内部**、`hidden=1` 的项目、其它 profile、Dock（默认） |

---

## 六、单元测试

```bash
./gradlew :app:testDebugUnitTest
```

- `ShuffleEngineTest` —— 锁定「范围 A/B 的语义」与「什么绝对不能动」
- `LauncherDbTest` —— Robolectric 起真实 SQLite，验证读 schema、事务写入、回读校验

不需要真机、不需要 Shizuku。
