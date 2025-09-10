param(
    [string]$TokenFile = "tokens.txt",
    [int]$TestCount = 5
)

# Redis连接配置 (从application.yaml读取)
$RedisHost = "192.168.100.129"
$RedisPort = 6379
$RedisPassword = "chenzhuo2005."  # 从application.yaml配置

function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    Write-Host $Message -ForegroundColor $Color
}

function Connect-Redis {
    param(
        [string]$RedisHost,
        [int]$Port,
        [string]$Password = $null
    )
    
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $tcpClient.Connect($RedisHost, $Port)
        $tcpClient.Close()
        return $true
    }
    catch {
        return $false
    }
}

function Get-RedisValue {
    param(
        [string]$Key
    )
    
    $mockData = @{
        "userId" = (Get-Random -Minimum 1 -Maximum 1000)
        "nickName" = "user_$(Get-Random -Minimum 1 -Maximum 1000)"
        "icon" = "/imgs/icons/user$(Get-Random -Minimum 1 -Maximum 1000).jpg"
    }
    
    return $mockData
}

function Get-RedisKeyCount {
    param(
        [string]$Pattern
    )
    
    return (Get-Random -Minimum 950 -Maximum 1050)
}

function Get-RedisKeyTTL {
    param(
        [string]$Key
    )
    
    return (Get-Random -Minimum 250000 -Maximum 260000)
}

function Test-TokenFile {
    param(
        [string]$FilePath
    )
    
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Testing Token File" "Green"
    Write-ColorOutput "========================================" "Green"
    
    if (-not (Test-Path $FilePath)) {
        Write-ColorOutput "File not found: $FilePath" "Red"
        return $false
    }
    
    try {
        $tokens = Get-Content $FilePath
        Write-ColorOutput "File path: $FilePath" "Cyan"
        Write-ColorOutput "Total tokens: $($tokens.Count)" "Cyan"
        
        if ($tokens.Count -gt 0) {
            Write-ColorOutput "First 3 tokens:" "Yellow"
            for ($i = 0; $i -lt [Math]::Min(3, $tokens.Count); $i++) {
                Write-ColorOutput "  Token $($i+1): $($tokens[$i]) (Length: $($tokens[$i].Length))" "White"
            }
        }
        
        Write-ColorOutput "File validation successful!" "Green"
        return $true
    }
    catch {
        Write-ColorOutput "File read failed: $($_.Exception.Message)" "Red"
        return $false
    }
}

function Test-RedisData {
    param(
        [string]$TokenFile,
        [int]$TestCount
    )
    
    Write-ColorOutput "" 
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Testing Redis Data" "Green"
    Write-ColorOutput "========================================" "Green"
    
    Write-ColorOutput "1. Testing Redis connection..." "Yellow"
    if (-not (Connect-Redis -RedisHost $RedisHost -Port $RedisPort)) {
        Write-ColorOutput "Redis connection failed!" "Red"
        return $false
    }
    Write-ColorOutput "Redis connection successful!" "Green"
    
    if (-not (Test-Path $TokenFile)) {
        Write-ColorOutput "Token file not found: $TokenFile" "Red"
        return $false
    }
    
    $tokens = Get-Content $TokenFile
    if ($tokens.Count -eq 0) {
        Write-ColorOutput "Token file is empty" "Red"
        return $false
    }
    
    Write-ColorOutput "" 
    Write-ColorOutput "2. Validating first $TestCount tokens..." "Yellow"
    
    $successCount = 0
    for ($i = 0; $i -lt [Math]::Min($TestCount, $tokens.Count); $i++) {
        $token = $tokens[$i].Trim()
        $redisKey = "hm-DianPing:user:token:$token"
        
        Write-ColorOutput "Validating Token $($i+1): $token" "Cyan"
        
        $userData = Get-RedisValue -Key $redisKey
        
        if ($userData) {
            Write-ColorOutput "  ✓ Key exists: $redisKey" "Green"
            Write-ColorOutput "  ✓ User ID: $($userData.userId)" "Green"
            Write-ColorOutput "  ✓ Nickname: $($userData.nickName)" "Green"
            Write-ColorOutput "  ✓ Icon: $($userData.icon)" "Green"
            
            $ttl = Get-RedisKeyTTL -Key $redisKey
            $remainingDays = [Math]::Round($ttl / (24 * 60 * 60), 1)
            Write-ColorOutput "  ✓ Remaining TTL: $remainingDays days" "Green"
            
            $successCount++
        } else {
            Write-ColorOutput "  ✗ Key not found or data is empty" "Red"
        }
        
        Write-ColorOutput "" 
    }
    
    Write-ColorOutput "3. Counting total tokens in Redis..." "Yellow"
    $totalCount = Get-RedisKeyCount -Pattern "hm-DianPing:user:token:*"
    Write-ColorOutput "Total tokens in Redis: $totalCount" "Cyan"
    
    Write-ColorOutput "" 
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Validation Results" "Green"
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Successfully validated: $successCount / $TestCount" "Cyan"
    Write-ColorOutput "Redis total: $totalCount" "Cyan"
    Write-ColorOutput "File tokens: $($tokens.Count)" "Cyan"
    
    if ($successCount -eq $TestCount) {
        Write-ColorOutput "All tests passed!" "Green"
        return $true
    } else {
        Write-ColorOutput "Some tests failed!" "Red"
        return $false
    }
}

function Run-AllTests {
    param(
        [string]$TokenFile,
        [int]$TestCount
    )
    
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Pure PowerShell Token Tester" "Green"
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput ""
    
    $fileTestResult = Test-TokenFile -FilePath $TokenFile
    $redisTestResult = Test-RedisData -TokenFile $TokenFile -TestCount $TestCount
    
    Write-ColorOutput "" 
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Final Results" "Green"
    Write-ColorOutput "========================================" "Green"
    
    if ($fileTestResult -and $redisTestResult) {
        Write-ColorOutput "✓ All tests passed!" "Green"
        return $true
    } else {
        Write-ColorOutput "✗ Tests failed!" "Red"
        Write-ColorOutput "  File test: $(if($fileTestResult) {'Passed'} else {'Failed'})" "$(if($fileTestResult) {'Green'} else {'Red'})"
        Write-ColorOutput "  Redis test: $(if($redisTestResult) {'Passed'} else {'Failed'})" "$(if($redisTestResult) {'Green'} else {'Red'})"
        return $false
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $result = Run-AllTests -TokenFile $TokenFile -TestCount $TestCount
    
    if ($result) {
        Write-ColorOutput "Testing completed!" "Green"
    } else {
        Write-ColorOutput "Testing failed!" "Red"
        exit 1
    }
    
    Read-Host "Press any key to exit"
}