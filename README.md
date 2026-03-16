# Ghost

一款 Android 悬浮球应用，专为观看视频设计。透明遮罩层防止误触，无障碍服务自动跳过 YouTube 广告。

---

## 功能

### 误触保护
- 启动后在屏幕上叠加一层透明拦截层，屏蔽所有误触操作
- 悬浮球始终保持在最顶层，长按 1.5 秒解除保护
- 解锁时震动反馈 + 弹跳动画确认

### 悬浮球
- 可拖拽至屏幕任意位置，松手自动吸附最近边缘（50% 隐藏）
- 1.5 秒无操作后自动半隐，触摸后恢复完整展示
- 点击展开扇形菜单，快速切换「锁定保护」和「跳广告开关」

### 自动跳广告
- 基于 AccessibilityService 监听 YouTube 界面节点变化
- 检测到可跳过广告按钮时自动点击（支持多语言按钮文本 + ViewId 双重识别）
- 可通过悬浮球菜单随时开关，无需重新授权

---

## 截图

> 待补充

---

## 技术架构

```
com.xyz.ghost
├── ui/                     # Activity 层
│   └── MainActivity        # 权限引导 + 服务开关
├── overlay/                # 悬浮层核心
│   ├── OverlayService      # Service 生命周期 + 组件装配（DI 根）
│   ├── ball/
│   │   ├── BallController  # 触摸手势 + 状态机 + 编排
│   │   ├── BallAnimator    # 所有动画：X 位移 / 解锁弹跳 / 小球出入 / 触觉
│   │   └── BallPositionCalc# 纯位置计算（hiddenX / revealedX / snapSide）
│   ├── menu/
│   │   └── MiniMenuController # 扇形小球菜单窗口 + 点击路由
│   └── protection/
│       └── ProtectionController # 全屏透明遮罩窗口生命周期
├── adskip/
│   └── AdSkipService       # AccessibilityService：YouTube 广告检测 + 点击
└── util/
    ├── NotificationHelper  # 前台通知构建
    └── ScreenUtils         # dpToPx / screenWidth / screenHeight 扩展函数
```

**关键设计决策**

| 问题 | 方案 |
|------|------|
| 解锁计时竞争条件 | 用 `SystemClock.elapsedRealtime()` 时间差替代 `Handler.postDelayed`，消除主线程竞争 |
| 缩放动画被裁切 | Window 尺寸 = 球体尺寸 + 20dp 四周溢出空间（96dp），保证 1.55x 缩放不越界 |
| Z-order 保证球在遮罩之上 | 激活保护时：removeView(ball) → addView(overlay) → addView(ball) |
| 高频 AccessibilityEvent 性能 | 用 `event.source` 做文本预检，仅命中关键词才获取完整节点树 |
| AdSkipService 无法代码开关 | SharedPreferences 标志位控制是否处理事件，服务常驻无需重新授权 |

---

## 构建要求

| 项目 | 要求 |
|------|------|
| Android Studio | Hedgehog 或更高 |
| JDK | 17（不兼容 GraalVM JDK 21） |
| minSdk | 24（Android 7.0） |
| compileSdk | 35 |

> **JDK 配置**：如遇 `jlink` 相关构建错误，在 `gradle.properties` 中添加：
> ```
> org.gradle.java.home=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
> ```

---

## 安装与使用

### 方式一：ADB 安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 方式二：Android Studio 直接运行

打开项目 → 连接设备 → Run

### 首次配置

1. 打开 Ghost → 点击「启动保护」→ 授予**悬浮窗权限**
2. 点击「前往授权」→ 在系统无障碍设置中启用 **Ghost**（跳广告功能可选）

### 使用流程

```
打开 YouTube → 开始播放
  → 点击悬浮球展开菜单
  → 点击「锁定」图标启动误触保护
  → 长按悬浮球 1.5 秒解除保护
```

---

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 悬浮球 + 透明遮罩层显示 |
| `FOREGROUND_SERVICE` | 服务后台常驻 |
| `VIBRATE` | 解锁时触觉反馈 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+）|
| `AccessibilityService` | 读取 YouTube 界面节点，自动跳广告 |

> AccessibilityService 在配置文件中声明 `packageNames="com.google.android.youtube"`，仅监听 YouTube 进程，不读取其他应用内容。

---

## 开发注意事项

- 不适合上架 Google Play（无障碍服务用途不符合平台政策）
- 仅供个人使用
- YouTube 界面更新可能导致跳广告功能失效，需更新 `AdSkipService` 中的按钮文本/ViewId

---

## License

MIT