Param(
  [switch]$Rebuild,
  [switch]$NoCompose,
  [switch]$Pretty
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Write-Ok($text) { Write-Host "[OK] $text" -ForegroundColor Green }
function Write-Fail($text) { Write-Host "[FAIL] $text" -ForegroundColor Red }

function Assert($cond, $msg) { if (-not $cond) { Write-Fail $msg; exit 1 } }

function Wait-TcpPort([string]$hostname, [int]$port, [int]$timeoutSec = 120) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
    try { if ((Test-NetConnection -ComputerName $hostname -Port $port -WarningAction SilentlyContinue).TcpTestSucceeded) { return $true } } catch {}
    Start-Sleep -Seconds 2
  }
  return $false
}

function BasicAuthHeader($user, $pass) {
  $pair = [System.Text.Encoding]::ASCII.GetBytes("$user`:$pass")
  $b64 = [System.Convert]::ToBase64String($pair)
  return @{ Authorization = "Basic $b64" }
}

function TryParseJson($text) {
  try { return $true, ($text | ConvertFrom-Json) } catch { return $false, $null }
}

function Invoke-Api {
  Param(
    [Parameter(Mandatory=$true)][ValidateSet('GET','POST','DELETE','PUT','PATCH')][string]$Method,
    [Parameter(Mandatory=$true)][string]$Url,
    [hashtable]$Headers,
    [string]$Body
  )
  Write-Host "$Method $Url" -ForegroundColor Yellow
  if ($Body) {
    Write-Host "Request body:"
    if ($Pretty) {
      try {
        $Body | ConvertFrom-Json | ConvertTo-Json -Depth 32 | Write-Host
      } catch {
        $Body | Write-Host
      }
    } else {
      $Body | Write-Host
    }
  }
  try {
    $params = @{
      Method = $Method
      Uri = $Url
      Headers = $Headers
      UseBasicParsing = $true
      ErrorAction = 'Stop'
    }
    if ($Body -and $Method -notin @('GET','DELETE','HEAD','OPTIONS')) {
      $params['Body'] = $Body
      $params['ContentType'] = 'application/json'
    }
    $resp = Invoke-WebRequest @params
    $status = [int]$resp.StatusCode
    $content = $resp.Content
    $ok = $status -ge 200 -and $status -lt 300
    Write-Host "Status: $status"
    $parsedOk, $json = TryParseJson $content
    if ($parsedOk) {
      if ($Pretty) {
        $json | ConvertTo-Json -Depth 32 | Write-Host
      } else {
        $content | Write-Host
      }
    } else {
      $content | Write-Host
    }
    return [pscustomobject]@{ StatusCode=$status; Ok=$ok; Raw=$content; Json=$json }
  } catch {
    $ex = $_.Exception
    $status = 0
    $content = ""
    
    # Try to get response from HttpResponseException (PowerShell)
    $response = $null
    $hasResponse = $ex.PSObject.Properties.Name -contains 'Response'
    if ($hasResponse) {
      try {
        $response = $ex.Response
        if ($null -ne $response) {
          $status = [int]$response.StatusCode.value__
          $content = $response.Content
        }
      } catch {
        # Failed to read response
      }
    }
    
    # Try WebException format if not found
    if ($status -eq 0 -and $ex -is [System.Net.WebException]) {
      try {
        $response = $ex.Response
        if ($null -ne $response) {
          $status = [int]$response.StatusCode.value__
          $stream = $response.GetResponseStream()
          $reader = New-Object System.IO.StreamReader($stream)
          $content = $reader.ReadToEnd()
          $reader.Close()
          $stream.Close()
        }
      } catch {
        # No response available
      }
    }
    
    if ($status -gt 0) {
      Write-Host "Status: $status"
      $parsedOk, $json = TryParseJson $content
      if ($parsedOk) {
        if ($Pretty) {
          $json | ConvertTo-Json -Depth 32 | Write-Host
        } else {
          $content | Write-Host
        }
      } else {
        if ($content) { $content | Write-Host } else { Write-Host "(empty response)" }
      }
      return [pscustomobject]@{ StatusCode=$status; Ok=$false; Raw=$content; Json=$json }
    } else {
      Write-Fail "Request failed: $($ex.Message)"
      return [pscustomobject]@{ StatusCode=0; Ok=$false; Raw=""; Json=$null }
    }
  }
}

Write-Step "Compose Up"
if (-not $NoCompose) {
  if ($Rebuild) { docker compose down -v | Out-Null }
  docker compose up -d --build | Out-Null
}

Write-Step "Wait for services"
Assert (Wait-TcpPort localhost 5432 180) "Postgres not ready"
Assert (Wait-TcpPort localhost 5672 180) "RabbitMQ not ready"
Assert (Wait-TcpPort localhost 15672 180) "RabbitMQ mgmt not ready"
Assert (Wait-TcpPort localhost 8081 180) "users-service not ready"
Assert (Wait-TcpPort localhost 8082 180) "accounts-service not ready"
Write-Ok "All services are ready"

# Prepare RabbitMQ bindings for capturing events
Write-Step "RabbitMQ declare queue and bindings"
$mgmtBase = 'http://localhost:15672/api'
$mgmtHdr = BasicAuthHeader 'guest' 'guest'
$queueName = 'test.audit'
$vhost = '%2f'
Invoke-RestMethod -Method Put -Uri "$mgmtBase/queues/$vhost/$queueName" -Headers $mgmtHdr -ContentType 'application/json' -Body (@{durable=$true; auto_delete=$false; arguments=@{}} | ConvertTo-Json)
foreach ($rk in @('user.registered','account.opened','card.issued','card.deleted')) {
  Invoke-RestMethod -Method Post -Uri "$mgmtBase/bindings/$vhost/e/bank.events/q/$queueName" -Headers $mgmtHdr -ContentType 'application/json' -Body (@{ routing_key=$rk; arguments=@{} } | ConvertTo-Json)
}
Write-Ok "Queue and bindings set"

# Test flow
$results = @()

$email = "user-$([Guid]::NewGuid().ToString())@example.com"
$password = 'pass1234'

Write-Step "Register"
$r1 = Invoke-Api -Method POST -Url 'http://localhost:8081/api/auth/register' -Body (@{ email=$email; password=$password; fullName='John Doe' } | ConvertTo-Json)
$r1Ok = if ($r1.PSObject.Properties.Name -contains 'Ok') { $r1.Ok } else { $false }
$userId = if ($r1.Json) { $r1.Json.id } else { $null }
$results += [pscustomobject]@{ Step='register'; Ok=$r1Ok; Status=$r1.StatusCode; Id=$userId }

Write-Step "Login"
$r2 = Invoke-Api -Method POST -Url 'http://localhost:8081/api/auth/login' -Body (@{ email=$email; password=$password } | ConvertTo-Json)
$token = if ($r2.Json) { $r2.Json.access_token } else { $null }
Assert ($token) "Login failed"
$auth = @{ Authorization = "Bearer $token" }
$r2Ok = if ($r2.PSObject.Properties.Name -contains 'Ok') { $r2.Ok } else { $false }
$results += [pscustomobject]@{ Step='login'; Ok=$r2Ok; Status=$r2.StatusCode; TokenLength=($token.Length) }

Write-Step "Users /me"
$r3 = Invoke-Api -Method GET -Url 'http://localhost:8081/api/users/me' -Headers $auth
$r3Ok = if ($r3.PSObject.Properties.Name -contains 'Ok') { $r3.Ok } else { $false }
$meId = if ($r3.Json -and $r3.Json.id) { $r3.Json.id } else { $null }
$results += [pscustomobject]@{ Step='me'; Ok=$r3Ok; Status=$r3.StatusCode; Id=$meId }

Write-Step "Open account"
$r4 = Invoke-Api -Method POST -Url 'http://localhost:8082/api/accounts' -Headers $auth -Body (@{ currency='USD' } | ConvertTo-Json)
$r4Ok = if ($r4.PSObject.Properties.Name -contains 'Ok') { $r4.Ok } else { $false }
$accountId = if ($r4.Json) { $r4.Json.id } else { $null }
$results += [pscustomobject]@{ Step='openAccount'; Ok=$r4Ok; Status=$r4.StatusCode; AccountId=$accountId }

Write-Step "Get balance"
$r5 = Invoke-Api -Method GET -Url "http://localhost:8082/api/accounts/$accountId/balance" -Headers $auth
$r5Ok = if ($r5.PSObject.Properties.Name -contains 'Ok') { $r5.Ok } else { $false }
$currency = if ($r5.Json -and $r5.Json.currency) { $r5.Json.currency } else { $null }
$balance = if ($r5.Json -and $r5.Json.balance) { $r5.Json.balance } else { $null }
$results += [pscustomobject]@{ Step='balance'; Ok=$r5Ok; Status=$r5.StatusCode; Currency=$currency; Balance=$balance }

Write-Step "Issue card"
$r6 = Invoke-Api -Method POST -Url "http://localhost:8082/api/accounts/$accountId/cards" -Headers $auth -Body (@{ holder='JOHN DOE' } | ConvertTo-Json)
$r6Ok = if ($r6.PSObject.Properties.Name -contains 'Ok') { $r6.Ok } else { $false }
$cardId = if ($r6.Json) { $r6.Json.id } else { $null }
$results += [pscustomobject]@{ Step='issueCard'; Ok=$r6Ok; Status=$r6.StatusCode; CardId=$cardId }

Write-Step "List cards"
$r7 = Invoke-Api -Method GET -Url "http://localhost:8082/api/accounts/$accountId/cards" -Headers $auth
$r7Ok = if ($r7.PSObject.Properties.Name -contains 'Ok') { $r7.Ok } else { $false }
$hasCard = $false
if ($r7.Json -and $cardId) {
  $hasCard = ($r7.Json | Where-Object { $_.id -eq $cardId } | Measure-Object).Count -ge 1
}
$results += [pscustomobject]@{ Step='listCards'; Ok=($r7Ok -and $hasCard); Status=$r7.StatusCode; Found=$hasCard }

Write-Step "Delete card"
$r8 = Invoke-Api -Method DELETE -Url "http://localhost:8082/api/cards/$cardId" -Headers $auth
$results += [pscustomobject]@{ Step='deleteCard'; Ok=($r8.StatusCode -ge 200 -and $r8.StatusCode -lt 300); Status=$r8.StatusCode }

# Read events
Write-Step "Read RabbitMQ events"
$getBody = @{ count=200; ackmode='ack_requeue_false'; encoding='auto'; truncate=50000 } | ConvertTo-Json
$msgs = Invoke-RestMethod -Method Post -Uri "$mgmtBase/queues/$vhost/$queueName/get" -Headers $mgmtHdr -ContentType 'application/json' -Body $getBody

function FindMsg($rk) { return $msgs | Where-Object { $_.routing_key -eq $rk } }
$eUser = FindMsg 'user.registered'
$eAcc  = FindMsg 'account.opened'
$eIss  = FindMsg 'card.issued'
$eDel  = FindMsg 'card.deleted'

Write-Ok "Events summary:"
@{
  'user.registered' = ($eUser | Select-Object -First 1).payload
  'account.opened'  = ($eAcc  | Select-Object -First 1).payload
  'card.issued'     = ($eIss  | Select-Object -First 1).payload
  'card.deleted'    = ($eDel  | Select-Object -First 1).payload
} | ConvertTo-Json -Depth 5 | Write-Host

$allOk = $results | Where-Object { -not $_.Ok } | Measure-Object | Select-Object -ExpandProperty Count
Write-Step "Summary"
$results | Format-Table -AutoSize | Out-String | Write-Host
if ($allOk -eq 0) { Write-Ok "SUCCESS: all steps passed"; exit 0 } else { Write-Fail "FAILED: some steps failed"; exit 1 }


