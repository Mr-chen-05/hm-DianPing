# HM-DianPing Token生成器使用说明

## 📋 项目概述

本项目提供了一套完整的Token生成和验证系统，用于HM-DianPing项目的用户认证。系统从MySQL数据库获取真实用户数据，生成UUID格式的Token，并将用户信息存储到Redis中。

## 🛠️ 环境要求

### 必需软件
- **Java 8+** - 用于运行MySQL数据获取程序
- **PowerShell 5.0+** - 用于运行脚本
- **Maven** - 用于编译Java项目

### 数据库配置
- **MySQL服务器**: 127.0.0.1:3306
- **数据库**: hmdp
- **用户**: root
- **密码**: chenzhuo2005.

### Redis配置
- **Redis服务器**: 192.168.100.129:6379
- **密码**: chenzhuo2005.

## 📁 项目结构

```
hm-dianping/
├── src/main/java/com/hmdp/utils/
│   └── MySQLUserFetcher.java          # MySQL用户数据获取程序
├── pure_token_generator.ps1           # Token生成脚本
├── pure_token_tester.ps1             # Token验证脚本
├── simple_verification.ps1           # 数据一致性验证脚本
├── tokens.txt                        # 生成的Token列表
├── pom.xml                           # Maven配置文件
└── README.md                         # 本说明文档
```

## 🚀 快速开始

### 1. 编译Java程序

```powershell
# 编译项目
mvn clean compile

# 下载依赖
mvn dependency:copy-dependencies
```

### 2. 生成Token

```powershell
# 运行Token生成脚本
powershell -ExecutionPolicy Bypass -File "pure_token_generator.ps1"
```

**生成参数**:
- Token数量: 1000个
- 输出文件: tokens.txt
- 有效期: 3天

### 3. 验证Token

```powershell
# 验证生成的Token
powershell -ExecutionPolicy Bypass -File "pure_token_tester.ps1"
```

### 4. 数据一致性检查

```powershell
# 检查Redis与MySQL数据一致性
powershell -ExecutionPolicy Bypass -File "simple_verification.ps1"
```

## 📝 脚本详细说明

### pure_token_generator.ps1

**功能**: 从MySQL获取用户数据并生成Token存储到Redis

**主要步骤**:
1. 测试Redis连接
2. 调用Java程序获取MySQL用户数据
3. 为每个用户生成UUID格式的Token
4. 将Token和用户信息存储到Redis Hash结构
5. 设置Token过期时间
6. 保存Token列表到文件

**Redis存储格式**:
```
Key: hm-DianPing:user:token:{UUID}
Type: Hash
Fields:
  - id: 用户ID
  - nick_name: 用户昵称
  - icon: 用户头像路径
```

### pure_token_tester.ps1

**功能**: 验证生成的Token是否正确存储在Redis中

**验证内容**:
- Token是否存在于Redis
- 用户数据是否完整
- Token剩余有效时间
- Redis中Token总数统计

### simple_verification.ps1

**功能**: 验证Redis中的数据与MySQL数据库的一致性

**验证方式**:
- 读取tokens.txt中的前10个Token
- 从Redis获取对应的用户数据
- 显示用户ID、昵称和头像信息

### MySQLUserFetcher.java

**功能**: 从MySQL数据库获取用户数据

**输出格式**: JSON数组，包含用户的id、nick_name、icon字段

## 🔧 配置修改

### 修改数据库连接

编辑 `pure_token_generator.ps1` 中的配置变量:

```powershell
# MySQL连接配置
$MySQLServer = "127.0.0.1"
$MySQLPort = 3306
$MySQLDatabase = "hmdp"
$MySQLUser = "root"
$MySQLPassword = "chenzhuo2005."

# Redis连接配置
$RedisHost = "192.168.100.129"
$RedisPort = 6379
$RedisPassword = "chenzhuo2005."
```

### 修改Token参数

编辑 `pure_token_generator.ps1` 底部的调用参数:

```powershell
$TokenCount = 1000        # Token数量
$OutputFile = "tokens.txt" # 输出文件
$ExpirationDays = 3       # 有效期(天)
```

## 📊 输出示例

### Token生成成功输出
```
========================================
Pure PowerShell Token Generator
========================================

1. Testing Redis connection...
Redis connection successful!

2. Getting user data...
正在从MySQL获取用户数据...
获取到 1000 个用户，其中 234 个用户有头像

3. Generating tokens...
Processed 100 users
Processed 200 users
...

4. Saving tokens to file...
Tokens saved to: tokens.txt

========================================
Generation completed!
========================================
Token count: 1000
Redis key format: hm-DianPing:user:token:{UUID}
Output file: tokens.txt
Expiration: 3 days
========================================
```

### 验证输出示例
```
Testing first 10 tokens from tokens.txt
=========================================
Token 1: 5059c3bdda1b42aa905daeb1727c17eb
  Redis: UserID=1, Nick=小鱼同学, Icon=/imgs/blogs/blog1.jpg

Token 2: 46c5570199fb4bc4adea42dcd22d1773
  Redis: UserID=2, Nick=可可今天不吃肉, Icon=/imgs/icons/kkjtbcr.jpg

...

Verification completed!
```

## ⚠️ 注意事项

1. **权限要求**: 确保PowerShell执行策略允许运行脚本
2. **网络连接**: 确保能够连接到MySQL和Redis服务器
3. **数据库权限**: MySQL用户需要有读取tb_user表的权限
4. **Redis清理**: 如需重新生成，请先清理Redis中的旧Token
5. **文件权限**: 确保有写入tokens.txt文件的权限

## 🐛 常见问题

### Q: Java程序编译失败
A: 检查Java版本和Maven配置，确保依赖正确下载

### Q: Redis连接失败
A: 检查Redis服务器地址、端口和密码配置

### Q: MySQL连接失败
A: 检查数据库服务器地址、用户名、密码和数据库名

### Q: Token验证失败
A: 检查Redis中的数据格式和字段名是否正确

## 📞 技术支持

如遇到问题，请检查:
1. 所有服务器连接是否正常
2. 配置参数是否正确
3. 权限设置是否充足
4. 日志输出中的错误信息

---

**版本**: 1.0  
**更新日期**: 2024年1月  
**作者**: HM-DianPing开发团队