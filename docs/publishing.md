# 发布到插件站

目前目标平台两个：**Hangar**（PaperMC 官方，Paper 插件优先）与 **Modrinth**（曝光最大）。

两个平台的分工不同，做法也不同：

| | Hangar | Modrinth |
|---|---|---|
| 项目创建 | 网页手动，**API 不提供建项目** | 网页或 `POST /v2/project`（需 PAT） |
| 上传版本 | `POST /api/v1/projects/{slug}/upload` | `POST /v2/version` |
| 认证 | `Authorization: HangarAuth <api-key>` | `Authorization: <PAT>` |
| 版本号唯一性 | 同一项目下**版本名不能重复**（跨频道也不行） | 同一项目下 `version_number` 不能重复 |
| 频道 | 需要先建 `Release` 频道（可设颜色） | `version_type: release / beta / alpha` |

## 一次性准备

### Hangar

1. 登录 <https://hangar.papermc.io>，右上角头像 → **New Project**。
2. 填写：Name `Copper Golem Sort`、Slug `copper-golem-sort`、Category `Gameplay`（或 `Utility`）、Platform **Paper**、License **MIT**。
3. 项目页 → **Channels** → 创建 `Release` 频道（必须给颜色，否则上传报 `version.new.error.channel.noColor`）。
4. 头像下拉 → **Settings** → **Api keys** → Create，**勾选 `create_version`**，复制 token（只显示一次）。

### Modrinth

1. 登录 <https://modrinth.com>，头像 → **Create a project**。
2. Project type **Plugin**，Loaders 只勾 **Paper**，Game versions 勾 `1.21.9`–`26.3`，License **MIT**，Source 填 `https://github.com/rayadesune/copper-golem-sort`。
3. 头像 → **Settings** → **Authorization** → **Personal access tokens** → Create，权限勾 **`PROJECT_CREATE`** 与 **`VERSION_CREATE`**。
4. 创建项目后，从项目页 URL 或 API 拿到 `project_id`（形如 `AABBCCDD`）。

## 项目元数据（两站共用）

| 字段 | 值 |
|---|---|
| Name | Copper Golem Sort |
| Slug | `copper-golem-sort` |
| Summary / Tagline | Makes Copper Golems sort your storage: persistent chest memory, gossip between golems, same-category deposits, post-deposit tidying, bounded give-up. |
| Categories | Storage · Utility · Management（Hangar：Gameplay / Utility） |
| Loaders | Paper only（依赖 Paper 专有事件，Spigot 不可用） |
| Game versions | 1.21.9 – 26.3 |
| Environment | Server only |
| License | MIT |
| Source | https://github.com/rayadesune/copper-golem-sort |
| Issues | https://github.com/rayadesune/copper-golem-sort/issues |
| Icon | `Images/icon-512.png`（512×512） |

## 上传命令

先设环境变量，**不要把 token 写进脚本或提交进仓库**：

```bash
export MODRINTH_TOKEN='mrp_...'
export HANGAR_TOKEN='...'
```

### Modrinth

```bash
VER=0.5.0
JAR=copper-golem-sort-${VER}.jar

cat > /tmp/mr-data.json <<JSON
{
  "name": "v${VER}",
  "version_number": "${VER}",
  "changelog": "见 https://github.com/rayadesune/copper-golem-sort/blob/main/CHANGELOG.md",
  "dependencies": [],
  "game_versions": ["1.21.9","1.21.10","1.21.11","26.1.2","26.2","26.3"],
  "version_type": "release",
  "loaders": ["paper"],
  "featured": false,
  "status": "listed",
  "project_id": "你的 project_id",
  "file_parts": ["file"],
  "primary_file": "file",
  "environment": "server_only"
}
JSON

curl -sS -X POST https://api.modrinth.com/v2/version \
  -H "Authorization: ${MODRINTH_TOKEN}" \
  -F "data=@/tmp/mr-data.json;type=application/json" \
  -F "file=@${JAR};type=application/java-archive"
```

### Hangar

```bash
VER=0.5.0
JAR=copper-golem-sort-${VER}.jar
SLUG=rayadesune/copper-golem-sort   # Hangar 的命名空间是 作者/项目

cat > /tmp/hg-data.json <<JSON
{
  "version": "${VER}",
  "channel": "Release",
  "platformDependencies": { "PAPER": ["1.21.9","1.21.10","1.21.11","26.1.2","26.2","26.3"] },
  "pluginDependencies": { "PAPER": [] },
  "description": "See https://github.com/rayadesune/copper-golem-sort/blob/main/CHANGELOG.md",
  "files": [ { "platforms": ["PAPER"] } ]
}
JSON

curl -sS -X POST "https://hangar.papermc.io/api/v1/projects/${SLUG}/upload" \
  -H "Authorization: HangarAuth ${HANGAR_TOKEN}" \
  -F "versionUpload=@/tmp/hg-data.json;type=application/json" \
  -F "files=@${JAR};type=application/octet-stream"
```

> Hangar 上传后进入**审核队列**，通过前不对外可见；Modrinth 是即时上线。

## 已发布记录

| 版本 | Hangar | Modrinth |
|---|---|---|
| 0.5.0 | 待发布 | 待发布 |
