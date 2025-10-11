# Orika Mapping Tracer

<div align="center">

![Build](https://github.com/wangfan1314/orika-helper/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID.svg)](https://plugins.jetbrains.com/plugin/PLUGIN_ID)

</div>

<!-- Plugin description -->
A powerful IntelliJ IDEA plugin designed specifically for analyzing and tracing field mapping relationships and call hierarchies in Orika mapping framework. Helps developers quickly understand complex object mapping logic in projects, improving development efficiency and code maintainability.

**Key Features:**
- 🔍 **Smart Mapping Analysis** - Automatically identifies and analyzes Orika mapping configurations 
- 🌲 **Complete Call Hierarchy** - Visualizes the complete call hierarchy structure of fields
- 🎯 **Precise Field Tracking** - Supports field-level precise mapping relationship analysis
- 🚀 **Quick Code Navigation** - Double-click nodes to jump to corresponding code locations
- 📊 **Multiple View Modes** - Provides mapping relationship view and call hierarchy view
- ⚡ **High-Performance Search** - Optimized search algorithms supporting large projects
<!-- Plugin description end -->

## ✨ Features

### 🔍 Mapping Relationship Analysis
- **Auto-Discovery Mapping** - Intelligently identifies Orika mapping calls in projects
- **Field-Level Validation** - Ensures accuracy of mapping relationships, avoiding false positives
- **Multiple Mapping Types** - Supports API configuration, annotation configuration, and other mapping methods
- **Cross-Module Support** - Supports mapping relationship analysis in multi-module projects

### 🌲 Call Hierarchy Tracing
- **Complete Call Hierarchy** - Full call chain from fields to Controller layer
- **Smart Node Classification** - Automatically identifies different layers like Controller, Service, Repository
- **Getter/Setter Tracking** - Automatically creates virtual getter/setter nodes ensuring complete hierarchy
- **Orika Mapping Enhancement** - Specially marks method nodes containing Orika mappings

### 🎯 User Experience
- **Keyboard Shortcuts** - Supports quick analysis triggering via shortcuts
- **Visual Interface** - Intuitive tree structure displaying call relationships
- **Code Navigation** - Double-click nodes to jump directly to corresponding code
- **Real-time Refresh** - Supports real-time refresh of analysis results

## 🚀 Quick Start

### Installation

#### Method 1: Install via JetBrains Plugin Marketplace
1. Open IntelliJ IDEA
2. Go to `File` → `Settings` → `Plugins`
3. Search for "orika-helper"
4. Click `Install` to install the plugin
5. Restart IDE

#### Method 2: Manual Installation
1. Download the latest plugin package from [Releases](https://github.com/wangfan1314/orika-helper/releases)
2. Open IntelliJ IDEA
3. Go to `File` → `Settings` → `Plugins`
4. Click the gear icon and select `Install Plugin from Disk...`
5. Select the downloaded plugin package file
6. Restart IDE

### Usage

#### 1. Analyze Mapping Relationships
1. In Java code, place cursor on the field you want to analyze
2. Use shortcut `Ctrl+Alt+Shift+M` or right-click menu to select "Show Mapping Relations"
3. The plugin will display all mapping relationships for that field in the bottom tool window

#### 2. View Call Hierarchy
1. In Java code, place cursor on the field you want to analyze
2. Use shortcut `Ctrl+Alt+Shift+O` or right-click menu to select "Show Call Hierarchy"
3. The plugin will display the complete call hierarchy structure for that field in the bottom tool window

#### 3. Code Navigation
- In the analysis result window, double-click any node to jump directly to the corresponding code location
- Use toolbar buttons to expand/collapse all nodes or refresh analysis results

## 📋 System Requirements

- **IntelliJ IDEA Version**: 2024.3.6 or higher
- **Java Version**: JDK 21 or higher
- **Project Type**: Supports Java projects, especially those using Orika mapping framework

## 🛠️ Development Guide

### Environment Setup
```bash
# Clone the project
git clone https://github.com/wangfan1314/orika-helper.git
cd orika-helper

# Build the project
./gradlew build

# Run the plugin (in IDE sandbox environment)
./gradlew runIde
```

### Project Structure
```
src/main/kotlin/com/github/wangfan1314/orikahelper/
├── actions/                    # User action handlers
│   └── OrikaMappingTracerAction.kt
├── callhierarchy/             # Call hierarchy analysis
│   ├── actions/
│   ├── model/
│   ├── services/
│   └── ui/
├── model/                     # Data models
│   ├── MappingCall.kt
│   └── MappingRelation.kt
├── services/                  # Core services
│   ├── OrikaMappingAnalyzer.kt
│   └── SimpleMappingAnalyzer.kt
├── ui/                        # User interface
└── utils/                     # Utility classes
```

### Core Components

#### OrikaMappingAnalyzer
Core service responsible for analyzing Orika mapping relationships, providing:
- Field mapping relationship analysis
- Call hierarchy construction
- Cross-module mapping support

#### CallHierarchyAnalyzer
Responsible for building and analyzing call hierarchy structures, integrating IDEA's native Call Hierarchy API.

#### CallHierarchyTreeWindow
Provides visual call hierarchy display interface with support for code navigation and interactive operations.

## 🤝 Contributing

We welcome contributions of any kind! Please check the [Contributing Guide](CONTRIBUTING.md) for detailed information.

### How to Contribute
1. Fork this project
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Create a Pull Request

## 📝 Changelog

See [CHANGELOG.md](CHANGELOG.md) for version update details.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Thanks to [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) for providing the project template
- Thanks to JetBrains for the powerful IntelliJ Platform SDK
- Thanks to all contributors and users for their support

## 📞 Contact

- **Project Homepage**: [https://github.com/wangfan1314/orika-helper](https://github.com/wangfan1314/orika-helper)
- **Issue Reports**: [Issues](https://github.com/wangfan1314/orika-helper/issues)
- **Feature Requests**: [Discussions](https://github.com/wangfan1314/orika-helper/discussions)

---

<div align="center">

**If this plugin helps you, please give us a ⭐ Star!**

</div>
