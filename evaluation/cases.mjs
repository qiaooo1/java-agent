export const knowledge = { title: '评估用员工与客服制度', content: '正式员工每年享有18天年假。客服服务时间为工作日09:00至18:00。报销单需要部门负责人审批。本文没有提供工资数额或奖金标准。' }; // 固定文档，评估输入可复现。
export const cases = [ // 覆盖工具计算、知识检索和缺少依据的回答。
  { id: 'arithmetic-basic', goal: '请务必调用 calculator 计算 1+2*3，返回结果。', expected: ['7'], tool: 'calculator' }, // 基础优先级测试。
  { id: 'arithmetic-parentheses', goal: '请务必调用 calculator 计算 (25+17)*8，返回结果。', expected: ['336'], tool: 'calculator' }, // 括号与乘法测试。
  { id: 'arithmetic-decimal', goal: '请务必调用 calculator 计算 100*0.85，返回结果。', expected: ['85'], tool: 'calculator' }, // 小数计算测试。
  { id: 'knowledge-leave', goal: '请检索知识文档，回答正式员工每年有多少天年假，并注明依据。', expected: ['18'], tool: 'search_knowledge', knowledge: true }, // 验证内部事实召回。
  { id: 'knowledge-hours', goal: '请检索知识文档，回答客服服务的开始和结束时间，并注明依据。', expected: ['09:00', '18:00'], tool: 'search_knowledge', knowledge: true }, // 验证多事实回答。
  { id: 'knowledge-abstain', goal: '请先检索知识文档：员工工资具体是多少？如果没有数额，请明确回答“资料未提供”，不要推测。', expected: ['资料未提供'], tool: 'search_knowledge', knowledge: true } // 缺少依据时的规则化拒答检查。
]; // 自动匹配不能替代人工对引用与语义的复核。
