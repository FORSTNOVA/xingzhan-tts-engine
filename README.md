# 星栈 Sherpa-ONNX 多音色本地 TTS 引擎 (Xingzhan TTS Engine)

基于 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 深度改造与扩展的 Android 本地离线语音合成引擎（TextToSpeechService）。

专为配合 **[星栈酒馆 (Xingzhan Tavern)](https://github.com/FORSTNOVA/xingzhan-tavern)** 跨引擎分流合成打造，提供零网络依赖、极低推理延迟、高音质的多角色发音支持。

---

## 🌟 核心特性

- **标准 Android 系统服务**：注册为标准 `android.intent.action.TTS_SERVICE`，可被系统全局 TTS 识别，亦可被星栈酒馆原生系统桥接无缝调用。
- **AIShell3 174 位说话人多音色枚举**：
  - 改造了底层 `TextToSpeechService` 的音色枚举与参数协商，支持上层应用直接枚举并按说话人 ID（0 ~ 173）精准选择音色。
  - 内置男女声分类与声学特征标记，支持角色对话根据性别与性格自动分流。
- **离线与轻量高效**：基于 ONNX Runtime 纯端侧本地推理，无需连网，不消耗云端 Token 与费用。
- **低延迟流式发音**：响应时间极低，对话流式输出时可近乎实时朗读。

---

## 📥 安装与下载

可以在本仓库的 [Releases 页面](https://github.com/FORSTNOVA/xingzhan-tts-engine/releases) 下载最新预编译 APK：
- **安装包文件**：`sherpa-onnx-tts-engine-vits-zh-aishell3-multi-voice.apk`

---

## 📱 使用指南

1. **安装 APK**：在手机上安装 `sherpa-onnx-tts-engine-vits-zh-aishell3-multi-voice.apk`。
2. **系统设置**：
   - 打开手机系统设置 -> 搜索「文字转语音」或「文本转语音 (TTS)」设置。
   - 首选引擎选择 **SherpaOnnx**。
   - 点击试听，确认可以正常发音。
3. **在星栈酒馆中使用**：
   - 打开星栈酒馆，进入语音合成 / 扩展设置。
   - 启用「系统 TTS / 本地 Sherpa 引擎」，即可自动识别到内置的全部 174 个发音角色并进行旁白、对话的精细化分流配音。

---

## 📄 许可与致谢

- 核心推理引擎源自 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0 License)。
- 模型基于开源 AIShell-3 数据集训练的 VITS 语音合成模型。
