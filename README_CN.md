# Orika Mapping Tracer

<div align="center">

![Build](https://github.com/wangfan1314/orika-helper/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)

</div>

<!-- Plugin description -->
一个强大的IntelliJ IDEA插件，专门用于分析和追踪Orika映射框架的字段映射关系和调用链路。帮助开发者快速理解复杂项目中的对象映射逻辑，提高开发效率和代码维护性。

**主要功能：**
- 🔍 **智能映射分析** - 自动识别和分析Orika映射配置 
- 🌲 **完整调用链路** - 可视化显示字段的完整调用层次结构
- 🎯 **精准字段追踪** - 支持字段级别的精确映射关系分析
- 🚀 **快速代码跳转** - 双击节点即可跳转到对应代码位置
- 📊 **多种视图模式** - 提供映射关系视图和调用链路视图
- ⚡ **高性能搜索** - 优化的搜索算法，支持大型项目
<!-- Plugin description end -->

## ✨ 功能特性

### 🔍 映射关系分析
- **自动发现映射** - 智能识别项目中的Orika映射调用
- **字段级别验证** - 确保映射关系的准确性，避免误报
- **多种映射类型** - 支持API配置、注解配置等多种映射方式
- **跨模块支持** - 支持多模块项目的映射关系分析

### 🌲 调用链路追踪
- **完整调用层次** - 从字段到Controller层的完整调用链路
- **智能节点分类** - 自动识别Controller、Service、Repository等不同层次
- **Getter/Setter追踪** - 自动创建虚拟getter/setter节点，确保链路完整性
- **Orika映射增强** - 特别标注包含Orika映射的方法节点

### 🎯 用户体验
- **快捷键操作** - 支持快捷键快速触发分析
- **可视化界面** - 直观的树形结构显示调用关系
- **代码跳转** - 双击节点直接跳转到对应代码位置
- **实时刷新** - 支持实时刷新分析结果

## 🚀 快速开始

### 安装方式

#### 方式一：通过JetBrains插件市场安装
1. 打开IntelliJ IDEA
2. 进入 `File` → `Settings` → `Plugins`
3. 搜索 "Orika Mapping Tracer"
4. 点击 `Install` 安装插件
5. 重启IDE

#### 方式二：手动安装
1. 从 [Releases](https://github.com/wangfan1314/orika-helper/releases) 页面下载最新版本的插件包
2. 打开IntelliJ IDEA
3. 进入 `File` → `Settings` → `Plugins`
4. 点击齿轮图标，选择 `Install Plugin from Disk...`
5. 选择下载的插件包文件
6. 重启IDE

### 使用方法

#### 1. 分析映射关系
1. 在Java代码中，将光标放置在要分析的字段上
2. 使用快捷键 `Ctrl+Alt+Shift+M` 或右键菜单选择 "显示映射关系"
3. 插件将在底部工具窗口中显示该字段的所有映射关系

#### 2. 查看调用链路
1. 在Java代码中，将光标放置在要分析的字段上
2. 使用快捷键 `Ctrl+Alt+Shift+O` 或右键菜单选择 "显示调用链路"
3. 插件将在底部工具窗口中显示该字段的完整调用层次结构

#### 3. 代码导航
- 在分析结果窗口中，双击任意节点可直接跳转到对应的代码位置
- 使用工具栏按钮可以展开/折叠所有节点或刷新分析结果

## 📋 系统要求

- **IntelliJ IDEA版本**: 2024.3.6 或更高版本
- **Java版本**: JDK 21 或更高版本
- **项目类型**: 支持Java项目，特别是使用Orika映射框架的项目

## 🛠️ 开发指南

### 环境准备
```bash
# 克隆项目
git clone https://github.com/wangfan1314/orika-helper.git
cd orika-helper

# 构建项目
./gradlew build

# 运行插件（在IDE沙盒环境中）
./gradlew runIde
```

### 项目结构
```
src/main/kotlin/com/github/wangfan1314/orikahelper/
├── actions/                    # 用户操作处理
│   └── OrikaMappingTracerAction.kt
├── callhierarchy/             # 调用层次分析
│   ├── actions/
│   ├── model/
│   ├── services/
│   └── ui/
├── model/                     # 数据模型
│   ├── MappingCall.kt
│   └── MappingRelation.kt
├── services/                  # 核心服务
│   ├── OrikaMappingAnalyzer.kt
│   └── SimpleMappingAnalyzer.kt
├── ui/                        # 用户界面
└── utils/                     # 工具类
```

### 核心组件

#### OrikaMappingAnalyzer
负责分析Orika映射关系的核心服务，提供：
- 字段映射关系分析
- 调用链路构建
- 跨模块映射支持

#### CallHierarchyAnalyzer
负责构建和分析调用层次结构，集成IDEA原生Call Hierarchy API。

#### CallHierarchyTreeWindow
提供可视化的调用链路展示界面，支持代码跳转和交互操作。

## 🤝 贡献指南

我们欢迎任何形式的贡献！请查看 [贡献指南](CONTRIBUTING.md) 了解详细信息。

### 如何贡献
1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 📝 更新日志

查看 [CHANGELOG.md](CHANGELOG.md) 了解版本更新详情。

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详细信息。

## 🙏 致谢

- 感谢 [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) 提供的项目模板
- 感谢 JetBrains 提供的强大的IntelliJ Platform SDK
- 感谢所有贡献者和用户的支持

## 📞 联系方式

- **项目主页**: [https://github.com/wangfan1314/orika-helper](https://github.com/wangfan1314/orika-helper)
- **问题反馈**: [Issues](https://github.com/wangfan1314/orika-helper/issues)
- **功能建议**: [Discussions](https://github.com/wangfan1314/orika-helper/discussions)

---

<div align="center">

**如果这个插件对你有帮助，请给我们一个 ⭐ Star！**

</div>