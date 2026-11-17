param([string]$BaseUrl = 'http://localhost:18080') # 可指定网关或平台地址验证相同鉴权规则。
$ErrorActionPreference = 'Stop' # 任一步失败即返回错误，避免脚本输出虚假的成功。
$smokeReadyDeadline = [DateTime]::UtcNow.AddSeconds(120) # 容器启动不等于 Java 应用已经可以服务。
while ($true) { # 在注册和提交之前等待真实入口就绪。
    try { $smokeHealth = Invoke-WebRequest "$BaseUrl/actuator/health" -TimeoutSec 3; if ($smokeHealth.StatusCode -eq 200) { break } } catch { if ([DateTime]::UtcNow -gt $smokeReadyDeadline) { throw 'Gateway/platform did not become healthy' } } # 健康失败只在启动窗口内重试。
    Start-Sleep -Milliseconds 500 # 不在服务尚未启动时形成连接忙循环。
} # 结束就绪检查。
$smokeUsername = 'smoke_' + [guid]::NewGuid().ToString('N').Substring(0, 12) # 每次使用独立账号，不修改现有用户。
$smokeCredentials = @{ username = $smokeUsername; password = 'smoke-password-123' } | ConvertTo-Json # 仅使用本地测试密码。
$smokeAuth = Invoke-RestMethod "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $smokeCredentials # 通过正式登录流程获取令牌。
$smokeHeaders = @{ Authorization = 'Bearer ' + $smokeAuth.accessToken; 'Idempotency-Key' = [guid]::NewGuid().ToString() } # 重复提交复用相同键。
$smokeInput = @{ goal = 'Calculate (25+17)*8'; knowledgeId = $null; maxSteps = 6 } | ConvertTo-Json # 未配置模型时走演示，真实模型可调用计算工具。
$smokeFirst = Invoke-RestMethod "$BaseUrl/api/tasks" -Method Post -ContentType 'application/json' -Headers $smokeHeaders -Body $smokeInput # 创建任务并预留额度。
$smokeSecond = Invoke-RestMethod "$BaseUrl/api/tasks" -Method Post -ContentType 'application/json' -Headers $smokeHeaders -Body $smokeInput # 模拟客户端重试。
if ($smokeFirst.id -ne $smokeSecond.id) { throw 'Idempotent submission created different tasks' } # 不允许重复预留或执行。
$smokeDeadline = [DateTime]::UtcNow.AddSeconds(90) # 整体等待时间有明确上限。
do { # 通过查询接口观察异步执行，而非假设消息已立即消费。
    Start-Sleep -Milliseconds 500 # 有界轮询，不形成忙循环。
    $smokeTask = Invoke-RestMethod "$BaseUrl/api/tasks/$($smokeFirst.id)" -Headers $smokeHeaders # 每次读取持久化任务状态。
    if ([DateTime]::UtcNow -gt $smokeDeadline) { throw 'Task did not finish before deadline' } # 故障时明确停止。
} while ($smokeTask.state -notin @('SUCCEEDED', 'FAILED', 'CANCELLED')) # 中间态不能被当作已完成。
if ($smokeTask.state -ne 'SUCCEEDED') { throw "Task failed: $($smokeTask.error)" } # 异步失败仍使烟雾测试失败。
$smokeEvents = Invoke-RestMethod "$BaseUrl/api/tasks/$($smokeFirst.id)/events?after=0" -Headers $smokeHeaders # 检查可持久化补读的轨迹。
if (-not ($smokeEvents | Where-Object eventType -eq 'done') -and -not ($smokeEvents | Where-Object type -eq 'done')) { throw 'Terminal event missing' } # 最终事件应与状态事务一致。
$smokeQuota = Invoke-RestMethod "$BaseUrl/api/quota" -Headers $smokeHeaders # 核对结算账本对账户的影响。
if ($smokeQuota.usedTasks -ne 1 -or $smokeQuota.reservedTasks -ne 0) { throw 'Quota settlement mismatch' } # 重复请求不能重复扣减。
Write-Output "PASS: task=$($smokeFirst.id), idempotency, async completion, events and settlement" # 仅所有检查完成后输出通过。
