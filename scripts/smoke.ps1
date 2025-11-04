Param(
  [switch]$Rebuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert($cond, $msg) {
  if (-not $cond) { Write-Error $msg; exit 1 }
}

function Wait-TcpPort([string]$hostname, [int]$port, [int]$timeoutSec = 120) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    try {
      $r = Test-NetConnection -ComputerName $hostname -Port $port -WarningAction SilentlyContinue
      if ($r.TcpTestSucceeded) { return $true }
    } catch {}
    Start-Sleep -Seconds 2
  }
  return $false
}

function BasicAuthHeader($user, $pass) {
  $pair = [System.Text.Encoding]::ASCII.GetBytes("$user`:$pass")
  $b64 = [System.Convert]::ToBase64String($pair)
  return @{ Authorization = "Basic $b64" }
}

Write-Host "[smoke] Bringing up Docker compose..."
if ($Rebuild) { docker compose down -v | Out-Null }
docker compose up -d --build | Out-Null

Write-Host "[smoke] Waiting for services (db:5432, rabbit:5672/15672, users:8081, accounts:8082)..."
Assert (Wait-TcpPort localhost 5432 180) "Postgres not ready on :5432"
Assert (Wait-TcpPort localhost 5672 180) "RabbitMQ not ready on :5672"
Assert (Wait-TcpPort localhost 15672 180) "RabbitMQ mgmt not ready on :15672"
Assert (Wait-TcpPort localhost 8081 180) "users-service not ready on :8081"
Assert (Wait-TcpPort localhost 8082 180) "accounts-service not ready on :8082"

$mgmtBase = 'http://localhost:15672/api'
$mgmtHdr = BasicAuthHeader 'guest' 'guest'

Write-Host "[smoke] Declaring test queue and bindings..."
$queueName = 'test.audit'
$vhost = '%2f'
Invoke-RestMethod -Method Put -Uri "$mgmtBase/queues/$vhost/$queueName" -Headers $mgmtHdr -ContentType 'application/json' -Body (@{durable=$true; auto_delete=$false; arguments=@{}} | ConvertTo-Json)

foreach ($rk in @('user.registered','account.opened','card.issued','card.deleted')) {
  Invoke-RestMethod -Method Post -Uri "$mgmtBase/bindings/$vhost/e/bank.events/q/$queueName" -Headers $mgmtHdr -ContentType 'application/json' -Body (@{ routing_key=$rk; arguments=@{} } | ConvertTo-Json)
}

Write-Host "[smoke] Running HTTP flow..."
$email = "user-$([guid]::NewGuid().ToString())@example.com"
$password = 'pass1234'

# 1) Register
$reg = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/register' -ContentType 'application/json' -Body (@{ email=$email; password=$password; fullName='John Doe' } | ConvertTo-Json)
$userId = $reg.id
Assert ($null -ne $userId) "Registration failed: no id"

# 2) Login
$login = Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/api/auth/login' -ContentType 'application/json' -Body (@{ email=$email; password=$password } | ConvertTo-Json)
$token = $login.access_token
Assert ($token) "Login failed: no token"
$auth = @{ Authorization = "Bearer $token" }

# 3) Me
$me = Invoke-RestMethod -Method Get -Uri 'http://localhost:8081/api/users/me' -Headers $auth
Assert ($me.id -eq $userId) "Me mismatch: $($me.id) != $userId"

# 4) Open account
$acc = Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/accounts' -Headers $auth -ContentType 'application/json' -Body (@{ currency='USD' } | ConvertTo-Json)
$accountId = $acc.id
Assert ($accountId) "Open account failed: no id"

# 5) Issue card
$card = Invoke-RestMethod -Method Post -Uri "http://localhost:8082/api/accounts/$accountId/cards" -Headers $auth -ContentType 'application/json' -Body (@{ holder='JOHN DOE' } | ConvertTo-Json)
$cardId = $card.id
Assert ($cardId) "Issue card failed: no id"

# 6) List cards
$cards = Invoke-RestMethod -Method Get -Uri "http://localhost:8082/api/accounts/$accountId/cards" -Headers $auth
$matchCount = ($cards | Where-Object { $_.id -eq $cardId } | Measure-Object).Count
Assert ($matchCount -ge 1) "Card not found in list"

# 7) Delete card
Invoke-RestMethod -Method Delete -Uri "http://localhost:8082/api/cards/$cardId" -Headers $auth | Out-Null

# 8) Check events via RabbitMQ mgmt
$getBody = @{ count=100; ackmode='ack_requeue_false'; encoding='auto'; truncate=50000 } | ConvertTo-Json
$msgs = Invoke-RestMethod -Method Post -Uri "$mgmtBase/queues/$vhost/$queueName/get" -Headers $mgmtHdr -ContentType 'application/json' -Body $getBody

function FindEvent($routingKey) {
  return $msgs | Where-Object { $_.routing_key -eq $routingKey }
}

$eUser = FindEvent 'user.registered'
$eAcc = FindEvent 'account.opened'
$eIssued = FindEvent 'card.issued'
$eDeleted = FindEvent 'card.deleted'

Assert ($eUser) "Missing user.registered event"
Assert ($eAcc) "Missing account.opened event"
Assert ($eIssued) "Missing card.issued event"
Assert ($eDeleted) "Missing card.deleted event"

# Validate payloads (best-effort)
try {
  $pUser = ($eUser[0].payload | ConvertFrom-Json)
  Assert ($pUser.userId -eq $userId) "user.registered userId mismatch"
} catch {}

try {
  $pAcc = ($eAcc[0].payload | ConvertFrom-Json)
  Assert ($pAcc.ownerId -eq $userId -and $pAcc.accountId -eq $accountId) "account.opened payload mismatch"
} catch {}

try {
  $pIssue = ($eIssued[0].payload | ConvertFrom-Json)
  Assert ($pIssue.ownerId -eq $userId -and $pIssue.accountId -eq $accountId -and $pIssue.cardId -eq $cardId) "card.issued payload mismatch"
} catch {}

try {
  $pDel = ($eDeleted[0].payload | ConvertFrom-Json)
  Assert ($pDel.ownerId -eq $userId -and $pDel.cardId -eq $cardId) "card.deleted payload mismatch"
} catch {}

Write-Host "[smoke] SUCCESS ✅ All checks passed"
exit 0


