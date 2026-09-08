# 铸件计数器 (Casting Counter)

一款专为铸件生产车间设计的计数与效率统计安卓应用。

## 功能特点

### 📊 任务管理
- 设置今日任务数量（件）
- 设置每箱装件数，自动计算所需箱数
- 实时显示完成进度

### 🔢 快捷计数
- **+5** 和 **+10** 快捷按钮
- 支持手动输入任意数量添加
- 语音识别添加：说出"加XX件"或"加XX"自动添加

### ⏰ 工时统计
- 设置上班时间和下班时间（24小时制）
- 支持夜班（+1天）模式
- 实时计算每小时产量

### 📈 数据分析
- 实时折线图展示产量趋势
- 推算预计完成时间
- 计算预计总耗时

### 💾 数据持久化
- 自动保存设置和进度
- 支持一键重置今日数据

## 技术栈

- Kotlin
- Android SDK 34
- ViewModel + LiveData
- MPAndroidChart (折线图)
- Android SpeechRecognizer (语音识别)
- SharedPreferences (数据存储)
- GitHub Actions (CI/CD)

## 构建

### 使用 Gradle 构建
```bash
./gradlew assembleDebug    # 构建 Debug 版本
./gradlew assembleRelease  # 构建 Release 版本
```

### GitHub Actions
项目配置了 GitHub Actions 自动构建，每次 push 到 main 分支都会自动构建 APK 并上传为 Artifact。

## 权限说明

- **RECORD_AUDIO**: 用于语音识别功能
- **INTERNET**: 用于语音识别服务（系统级）

## License

MIT
