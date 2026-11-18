# 维护日志

只记录实际完成的改进和验证；提交、推送和 GitHub Actions 的云端运行结果以仓库实际记录为准。

## 2026-11-18：加入持续集成检查

### 问题与改进

项目已有核心、事务、API 等回归测试，但缺少 GitHub 自动执行入口。维护时依赖人工记住运行测试，容易让构建或语法问题随更新进入仓库。

新增 `.github/workflows/ci.yml`，在推送、PR 或手动触发时执行 Java 17 全模块构建及默认回归测试，并独立检查前端与烟雾测试、评估脚本的 JavaScript 语法。测试报告保留七天，方便排查失败；同一分支的新运行取消旧运行。README 与测试指南补充检查入口、环境及验证边界。

### 验证

- `mvn -B -ntp verify`：本机 Windows / Java 17 构建成功，全部八个 reactor 项目成功；11 个测试套件共 54 个测试，失败、错误和跳过均为 0。原始日志为本机忽略目录下的 `target/daily-ci-verify.log`。
- Node.js 24：前端 `app.js` 与 `scripts/`、`evaluation/` 中的八个 `.mjs` 文件，共九个文件语法检查通过。
- `git diff --check`：通过。
- 工作流 YAML 解析通过，确认推送、PR、手动触发配置及两个检查任务均可读取；这不替代 GitHub 云端实际执行。
- 工作流中四个 GitHub Action 的固定提交已与官方仓库版本标签核对；报告上传参数已按对应版本的 `action.yml` 核对。
- 尚未执行 GitHub 云端运行、Docker 集成测试或真实模型评估。首次推送后需要在 Actions 中确认 Ubuntu 环境的实际结果。

### 提交建议

`ci: add Java 17 regression build and JavaScript syntax checks`
