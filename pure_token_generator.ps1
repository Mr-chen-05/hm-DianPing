param(
    [int]$TokenCount = 1000,
    [string]$OutputFile = "tokens.txt",
    [int]$ExpirationDays = 3
)

# Redis连接配置 (从application.yaml读取)
$RedisHost = "192.168.100.129"
$RedisPort = 6379
$RedisPassword = "chenzhuo2005."  # 从application.yaml配置

# MySQL连接配置 (从application.yaml读取)
$MySQLServer = "127.0.0.1"
$MySQLPort = 3306
$MySQLDatabase = "hmdp"
$MySQLUser = "root"
$MySQLPassword = "chenzhuo2005."  # 从application.yaml配置

function Write-ColorOutput {
    param(
        [string]$Message,
        [string]$Color = "White"
    )
    Write-Host $Message -ForegroundColor $Color
}

function Generate-UUID {
    return [System.Guid]::NewGuid().ToString().Replace("-", "")
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

function Get-MySQLUsers {
    param(
        [string]$Server,
        [int]$Port,
        [string]$Database,
        [string]$User,
        [string]$Password,
        [int]$Limit = 1000
    )
    
    try {
        Write-ColorOutput "正在从MySQL获取用户数据..." "Yellow"
        
        # 调用Java程序获取MySQL用户数据
        $javaOutput = java -cp "target/classes;target/dependency/*" com.hmdp.utils.MySQLUserFetcher $Limit 2>&1
        
        if ($javaOutput -match "^\[") {
            $users = $javaOutput | ConvertFrom-Json
        } else {
            Write-Host "Java程序输出: $javaOutput"
            return @()
        }
        $usersWithIcon = ($users | Where-Object { $_.icon -ne "" }).Count
         Write-ColorOutput "获取到 $($users.Count) 个用户，其中 $usersWithIcon 个用户有头像" "Green"
         return $users
    }
    catch {
        Write-ColorOutput "获取MySQL数据失败: $($_.Exception.Message)" "Red"
        return @()
    }
}

function Store-TokenToRedis {
    param(
        [string]$Token,
        [PSCustomObject]$UserData,
        [int]$ExpirationSeconds
    )
    
    try {
        # Redis Hash结构存储用户信息
        $redisKey = "hm-DianPing:user:token:$Token"
        
        # 实际连接Redis并存储数据
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $tcpClient.Connect($RedisHost, $RedisPort)
        $stream = $tcpClient.GetStream()
        $writer = New-Object System.IO.StreamWriter($stream)
        $reader = New-Object System.IO.StreamReader($stream)
        
        # Redis AUTH命令
        if ($RedisPassword) {
            $writer.WriteLine("AUTH $RedisPassword")
            $writer.Flush()
            $response = $reader.ReadLine()
        }
        
        # HSET命令存储用户信息到Hash结构
        $writer.WriteLine("HSET $redisKey id $($UserData.id) nick_name `"$($UserData.nick_name)`" icon `"$($UserData.icon)`"")
        $writer.Flush()
        $response = $reader.ReadLine()
        
        # 设置过期时间
        $writer.WriteLine("EXPIRE $redisKey $ExpirationSeconds")
        $writer.Flush()
        $response = $reader.ReadLine()
        
        $writer.Close()
        $reader.Close()
        $tcpClient.Close()
        
        Write-ColorOutput "Stored token $Token for user $($UserData.id) in Redis Hash" "Cyan"
        return $true
    }
    catch {
        Write-ColorOutput "Redis storage failed for token $Token : $($_.Exception.Message)" "Red"
        return $false
    }
}

function Generate-Tokens {
    param(
        [int]$Count,
        [string]$OutputFile,
        [int]$ExpirationDays
    )
    
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Pure PowerShell Token Generator" "Green"
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput ""
    
    Write-ColorOutput "1. Testing Redis connection..." "Yellow"
    if (-not (Connect-Redis -RedisHost $RedisHost -Port $RedisPort)) {
        Write-ColorOutput "Redis connection failed!" "Red"
        return $false
    }
    Write-ColorOutput "Redis connection successful!" "Green"
    Write-ColorOutput ""
    
    Write-ColorOutput "2. Getting user data..." "Yellow"
    $users = Get-MySQLUsers -Server $MySQLServer -Port $MySQLPort -Database $MySQLDatabase -User $MySQLUser -Password $MySQLPassword -Limit $Count
    
    if ($users.Count -eq 0) {
        Write-ColorOutput "Cannot get user data!" "Red"
        return $false
    }
    
    Write-ColorOutput "3. Generating tokens..." "Yellow"
    $tokens = @()
    $expirationSeconds = $ExpirationDays * 24 * 60 * 60
    
    for ($i = 0; $i -lt $users.Count; $i++) {
        $token = Generate-UUID
        $user = $users[$i]
        
        Store-TokenToRedis -Token $token -UserData $user -ExpirationSeconds $expirationSeconds
        $tokens += $token
        
        if (($i + 1) % 100 -eq 0) {
            Write-ColorOutput "Processed $($i + 1) users" "Cyan"
        }
    }
    
    Write-ColorOutput "4. Saving tokens to file..." "Yellow"
    try {
        $tokens | Out-File -FilePath $OutputFile -Encoding UTF8
        Write-ColorOutput "Tokens saved to: $OutputFile" "Green"
    }
    catch {
        Write-ColorOutput "File save failed: $($_.Exception.Message)" "Red"
        return $false
    }
    
    Write-ColorOutput "" 
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Generation completed!" "Green"
    Write-ColorOutput "========================================" "Green"
    Write-ColorOutput "Token count: $($tokens.Count)" "Cyan"
    Write-ColorOutput "Redis key format: hm-DianPing:user:token:{UUID}" "Cyan"
    Write-ColorOutput "Output file: $OutputFile" "Cyan"
    Write-ColorOutput "Expiration: $ExpirationDays days" "Cyan"
    Write-ColorOutput "========================================" "Green"
    
    return $true
}

if ($MyInvocation.InvocationName -ne '.') {
    $result = Generate-Tokens -Count $TokenCount -OutputFile $OutputFile -ExpirationDays $ExpirationDays
    
    if ($result) {
        Write-ColorOutput "All operations completed successfully!" "Green"
    } else {
        Write-ColorOutput "Operation failed!" "Red"
        exit 1
    }
    
    Read-Host "Press any key to exit"
}