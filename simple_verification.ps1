# Simple token verification script
$RedisHost = "192.168.100.129"
$RedisPort = 6379
$RedisPassword = "chenzhuo2005."

# Read first 10 tokens from file
$tokens = Get-Content "tokens.txt" | Select-Object -First 10

Write-Host "Testing first 10 tokens from tokens.txt" -ForegroundColor Yellow
Write-Host "=========================================" -ForegroundColor Yellow

foreach ($i in 0..9) {
    $token = $tokens[$i]
    $redisKey = "hm-DianPing:user:token:$token"
    
    Write-Host "Token $($i+1): $token" -ForegroundColor Cyan
    
    try {
        # Connect to Redis
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $tcpClient.Connect($RedisHost, $RedisPort)
        $stream = $tcpClient.GetStream()
        $writer = New-Object System.IO.StreamWriter($stream)
        $reader = New-Object System.IO.StreamReader($stream)
        
        # AUTH
        $writer.WriteLine("AUTH $RedisPassword")
        $writer.Flush()
        $authResponse = $reader.ReadLine()
        
        # HGETALL
        $writer.WriteLine("HGETALL $redisKey")
        $writer.Flush()
        
        $response = $reader.ReadLine()
        
        if ($response -match "^\*(\d+)") {
            $fieldCount = [int]$matches[1]
            
            if ($fieldCount -gt 0) {
                $hashData = @{}
                
                for ($j = 0; $j -lt $fieldCount; $j += 2) {
                    $fieldLengthLine = $reader.ReadLine()
                    $fieldName = $reader.ReadLine()
                    $valueLengthLine = $reader.ReadLine()
                    $fieldValue = $reader.ReadLine()
                    $hashData[$fieldName] = $fieldValue
                }
                
                $userId = $hashData["id"]
                $nickName = $hashData["nick_name"]
                $icon = $hashData["icon"]
                
                Write-Host "  Redis: UserID=$userId, Nick=$nickName, Icon=$icon" -ForegroundColor Green
            } else {
                Write-Host "  Redis: No data found" -ForegroundColor Red
            }
        } else {
            Write-Host "  Redis: Key not found" -ForegroundColor Red
        }
        
        $writer.Close()
        $reader.Close()
        $tcpClient.Close()
    }
    catch {
        Write-Host "  Redis Error: $($_.Exception.Message)" -ForegroundColor Red
    }
    
    Write-Host ""
}

Write-Host "Verification completed!" -ForegroundColor Yellow
Read-Host "Press Enter to exit"