# Aura

Aura 是一个 Android 动态壁纸应用，支持以下内容类型：

- 在线网页壁纸
- 直接返回图片或视频的 API
- 本地图片 / 视频 / HTML 壁纸
- 本地视频 360° VR 壁纸
- 网页批量轮播和本地批量轮播
- 图片三种显示模式：完整显示 / 裁切铺满 / 拉伸填充

## 项目结构

```text
Aura/
├─ app/                           Android 应用模块
├─ gradle/                        Gradle Wrapper 配置
├─ scripts/                       辅助脚本
├─ clean.sh                       清理本地缓存和非发布文件
├─ build.gradle.kts               顶层构建脚本
├─ settings.gradle.kts
├─ gradle.properties
└─ README.md
```

核心代码入口：

- `app/src/main/java/com/example/bizhi/MainActivity.kt`
- `app/src/main/java/com/example/bizhi/wallpaper/WebWallpaperService.kt`
- `app/src/main/java/com/example/bizhi/data/WallpaperPreferences.kt`
- `app/src/main/java/com/example/bizhi/media/LocalVideoPlayer.kt`

## 环境要求

- JDK 17
- Android SDK
- Android platform 34
- Android build-tools 34.0.0

推荐通过 `local.properties` 或环境变量提供 SDK 路径，例如：

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_USER_HOME=$PWD/.android-user
```

## 构建

编译校验：

```bash
GRADLE_USER_HOME=$PWD/.gradle-local ANDROID_USER_HOME=$PWD/.android-user ./gradlew --no-daemon :app:compileReleaseKotlin
```

打包 release：

```bash
GRADLE_USER_HOME=$PWD/.gradle-local ANDROID_USER_HOME=$PWD/.android-user ./gradlew --no-daemon :app:assembleRelease
```

产物位置：

```text
app/build/outputs/apk/release/app-release.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

如果 `adb` 不在 PATH，可使用：

```bash
$ANDROID_SDK_ROOT/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

