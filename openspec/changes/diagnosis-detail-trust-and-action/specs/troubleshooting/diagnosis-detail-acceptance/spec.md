## Purpose

把「详情可用」从主观感觉变成可交付的口测记录，并限制失败时的修复方式。

## ADDED Requirements

### Requirement: Two-role ten-second gate before claiming done
在将本变更标为完成前，MUST 完成：
1. 未参与本详情设计的二线：10 秒内回答「要不要升级三线」；
2. 未参与本详情设计的三线：10 秒内回答「根因找到了吗 / 现在知道什么 / 下一步查什么」。
任一失败 MUST 只允许删减或改写首屏信息，MUST NOT 新增第二套详情或默认展开判定链。

#### Scenario: Failed support answer blocks done
- **WHEN** 二线口测答错或超时
- **THEN** tasks 中验收项 MUST 保持未完成，直到减法改版后重测通过

#### Scenario: Failed developer answer blocks done
- **WHEN** 三线口测对根因状态、已知项或下一步任一答错
- **THEN** tasks 中验收项 MUST 保持未完成，直到减法改版后重测通过

### Requirement: Gate record is a repo artifact
口测结果 MUST 写入仓库文档（建议路径：`docs/intelligent-troubleshooting/diagnosis-detail-misread-gate.md`），包含：日期、角色、DiagnosisId、通过/失败、误读点、所用视角。

#### Scenario: Passing leaves a markdown record
- **WHEN** 二线与三线口测均通过
- **THEN** 上述文档 MUST 存在且含两次通过记录与 DiagnosisId
