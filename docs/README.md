# 文档索引

本目录分两类：

- **`guides/`** —— **现行手册**，可以照着做；
- **`audits/`、`deploy/`、`testing/`** —— **历史档案**，记录当时发生了什么，结论与行号以当时代码为准，**不代表当前状态**。

项目入口在仓库根 [`../README.md`](../README.md)。

## guides/ — 现行手册

| 文档 | 用途 |
|---|---|
| [`ARCHITECTURE.md`](guides/ARCHITECTURE.md) | 架构与关键链路（为什么这样设计） |
| [`DEPLOY.md`](guides/DEPLOY.md) | 部署手册：把服务端搬到一台常开的服务器 |
| [`RELEASE.md`](guides/RELEASE.md) | 发版步骤、更新机制原理、红线与踩坑清单 |
| [`RELEASE-RULES.md`](guides/RELEASE-RULES.md) | 发布规则（A/B 段流程、B1/B2 等） |

## audits/ — 审计与体检报告（历史）

| 文档 | 原位置 | 内容 |
|---|---|---|
| [`HYGIENE-AUDIT-REPORT.md`](audits/HYGIENE-AUDIT-REPORT.md) | 仓库根 | 仓库卫生、构建配置与发布流程审计 |
| [`HEALTH-CHECK-2026-09-18.md`](audits/HEALTH-CHECK-2026-09-18.md) | `miyu-app/` | 全面体检报告（H1~H4 等问题清单） |
| [`E2EE_SECURITY_AUDIT.md`](audits/E2EE_SECURITY_AUDIT.md) | `miyu-app/` | 端到端加密专项审计（TOFU pin 等） |
| [`server-AUDIT-REPORT.md`](audits/server-AUDIT-REPORT.md) | `miyu-app/server-java/` | 服务端安全审计 |
| [`client-AUDIT-REPORT.md`](audits/client-AUDIT-REPORT.md) | `miyu-app/apk/FamilyChatApp/` | 客户端安全审计 |
| [`LOGIC-AUDIT-2026-09-21.md`](audits/LOGIC-AUDIT-2026-09-21.md) | `miyu-app/server-java/` | 服务端业务逻辑审计 |

> 两份同名 `AUDIT-REPORT.md` 归档时按端侧重新命名为 `server-` / `client-` 前缀，便于区分。

### audits/apk-av/ — 报毒取证（历史）

| 文档 | 内容 |
|---|---|
| [`报毒原因分析报告.md`](audits/apk-av/报毒原因分析报告.md) | 被杀软判为"危险捆绑软件"的完整取证与整改依据 |
| [`报毒原因分析报告-补充-OPPO实测.md`](audits/apk-av/报毒原因分析报告-补充-OPPO实测.md) | OPPO 安装器实测补充证据 |

> 这两份是 [`RELEASE.md`](guides/RELEASE.md)「禁止用调试签名出包」红线的取证基础；原目录 `_apkcheck/` 已归档至此。

## deploy/ — 单次部署记录（历史）

| 文档 | 原位置 | 内容 |
|---|---|---|
| [`DEPLOY-2026-09-18-server-6-fixes.md`](deploy/DEPLOY-2026-09-18-server-6-fixes.md) | `miyu-app/` | 2026-09-18 服务端 6 项修复的部署与回滚记录 |

## testing/ — 手工验收清单（历史）

| 文档 | 原位置 | 内容 |
|---|---|---|
| [`MANUAL-TEST-chat-pin-quiet.md`](testing/MANUAL-TEST-chat-pin-quiet.md) | `miyu-app/apk/FamilyChatApp/` | 聊天置顶/免打扰的真机手工验收清单 |

## assets/ — 资源

| 目录 | 内容 |
|---|---|
| [`design-icons/`](assets/design-icons/) | 早期界面图标的 PNG 源文件（27 个，供 UI 参考与替换） |

---

**不在本目录的运维文档：**

- [`../release/签名密钥备份说明.md`](../release/签名密钥备份说明.md) — 紧贴签名密钥，移走会割裂上下文。
- [`../release/DELETED-APK-BACKUPS-20260923.txt`](../release/DELETED-APK-BACKUPS-20260923.txt) — 历史删除清单，是已删备份的唯一凭证。
