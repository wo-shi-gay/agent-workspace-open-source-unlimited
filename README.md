# Agent Workspace 开源源码副本

开源首发：5.2.2-open-source / versionCode 66，独立包名 `com.agentpad.workspace.opensource`，可与官网版共存。

公开仓库：https://github.com/vxggm/agent-workspace-open-source

[项目官网](https://xn--agent666-df8sk40v.ddns.ge/) · [开源版下载](https://github.com/vxggm/agent-workspace-open-source/releases)

## 人人可参与更新

欢迎所有人 Fork、修改和发起 Pull Request，代码、文档、翻译与问题修复都欢迎。完整步骤见 [CONTRIBUTING.md](CONTRIBUTING.md)。

- [提交修改 / Pull Requests](https://github.com/vxggm/agent-workspace-open-source/pulls)
- [问题反馈 / Issues](https://github.com/vxggm/agent-workspace-open-source/issues)
- [交流讨论 / Discussions](https://github.com/vxggm/agent-workspace-open-source/discussions)

公开意味着所有人均可读取、克隆、Fork与提交贡献；原仓库直接推送权限由协作者角色控制，PR由维护者合并。

下载版本：https://github.com/vxggm/agent-workspace-open-source/releases

## 目录范围

本目录是独立 Android Gradle 工程，包含 App Kotlin/Java/C 源码、XML、资源、Gradle 配置与 Wrapper。服务器后端工程、生产部署配置、历史交接、私有凭据、签名密钥、APK及构建缓存均未收录。原工作区保持原样。

图片、字体、图标和项目模板属于运行资源。`app/src/main/jniLibs/` 保留已有 arm64 终端/proot 运行库；`gradle/wrapper/gradle-wrapper.jar` 是官方构建引导文件。这两类是既有工程所需的二进制依赖，并非新编译的交付包；目录并非所有文件都为纯文本。Rhino 改由 Maven Central 的 `org.mozilla:rhino:1.7.15.1` 解析，未复制本地依赖JAR。

## 已替换的私有配置

- 用户系统后端基址、官方模型基址、收款链接及自有环境下载链接统一使用 `https://example.com`。
- 市场构建凭证注入段中的固定HMAC密钥替换为 `https://example.com`；部署自建市场时请自行配置匹配的服务端凭证。
- 本项目维护者的 GitHub 工具链下载链接及网盘分享链接同样换为 `https://example.com`，自行部署资源后配置对应常量。
- 用户登录后动态保存的token、用户自行输入的模型Key、MCP/SSH配置逻辑保留，副本未携带用户运行数据。
- 公共依赖仓库、上游源码引用、Android XML/SVG命名空间和本机127.0.0.1服务地址保留。

占位地址仅用于公开源码，自建后端与工具资源配置完成后相应联网功能才可使用。

## 构建

使用 JDK17、Android SDK37，Gradle Wrapper9.6.0，AGP9.4.0，Compose Kotlin插件2.4.21。SDK安装路径可通过 `ANDROID_HOME` 配置，或参照 `local.properties.example` 创建自己的 `local.properties`。

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

发布签名从用户自己的 `local.properties` 或 `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` 环境变量读取。首发标签v5.2.2-open-source已通过GitHub Actions release/R8构建，APK签名与下载SHA已核验；功能按真机反馈继续迭代。

## 上游与许可

RikkaHub、Operit、Termux及其他依赖的来源与文件内声明保留。根目录LICENSE复制自现有 `app/src/main/assets/RikkaHub-LICENSE.txt`；该文件及各源码头部许可证说明请随分发保留。终端/proot运行库的源码与构建资料参见上游项目，未将工作区内整套参考仓库拷入本目录。

## GitHub Actions 发版

推送 `v*` 标签触发 `.github/workflows/release.yml`：安装SDK37、构建R8 release、验证签名、上传APK及SHA256SUMS并创建Release。也可手动触发构建，仅标签构建自动发布。

仓库使用独立开源签名，Secrets为 `OPEN_SOURCE_KEYSTORE_BASE64`、`OPEN_SOURCE_KEYSTORE_PASSWORD`、`OPEN_SOURCE_KEY_ALIAS`；密钥本体与口令均留在仓库之外。Fork后自行配置自己的签名Secrets。

本目录保留源码形式，APK随Release单独提供。对应联网功能使用占位地址，自建配置后使用。
