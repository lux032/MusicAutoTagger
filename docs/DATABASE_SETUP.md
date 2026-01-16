# MySQL数据库日志系统配置说明

## 概述

系统已升级为使用MySQL数据库存储已处理文件的日志记录,相比纯文本日志具有以下优势:

- ✅ **更高的查询效率** - 使用索引快速检索记录
- ✅ **并发安全** - 支持多进程同时访问
- ✅ **数据完整性** - 事务支持和约束保护
- ✅ **统计分析** - 方便进行数据统计和报表生成
- ✅ **扩展性强** - 易于添加新字段和功能

## 1. 安装MySQL数据库

### Windows系统

1. 下载MySQL安装包: https://dev.mysql.com/downloads/mysql/
2. 运行安装程序,选择"Developer Default"配置
3. 设置root密码(建议设置为强密码)
4. 完成安装后,MySQL服务将自动启动

### 验证安装

打开命令提示符,执行:
```bash
mysql --version
```

## 2. 创建数据库和表

### 方法一: 使用SQL脚本(推荐)

1. 登录MySQL:
```bash
mysql -u root -p
```

2. 执行项目中的SQL脚本:
```sql
source D:/MyPlan/MusicDemo/src/main/resources/schema.sql
```

### 方法二: 手动创建

```sql
-- 创建数据库
CREATE DATABASE IF NOT EXISTS music_demo 
DEFAULT CHARACTER SET utf8mb4 
DEFAULT COLLATE utf8mb4_unicode_ci;

USE music_demo;

-- 创建表(表结构会由程序自动创建)
```

### 验证数据库创建

```sql
-- 查看数据库
SHOW DATABASES;

-- 使用数据库
USE music_demo;

-- 查看表结构
SHOW TABLES;
DESC processed_files;
```

## 3. 配置应用程序

编辑 `config.properties` 文件:

```properties
# 数据库类型(使用mysql)
db.type=mysql

# MySQL数据库连接配置
db.mysql.host=localhost          # 数据库服务器地址
db.mysql.port=3306              # 端口号
db.mysql.database=music_demo    # 数据库名称
db.mysql.username=root          # 用户名
db.mysql.password=your_password # 密码(请修改为实际密码)

# 连接池配置
db.mysql.pool.maxPoolSize=10        # 最大连接数
db.mysql.pool.minIdle=2             # 最小空闲连接数
db.mysql.pool.connectionTimeout=30000  # 连接超时时间(毫秒)
```

**重要提示:**
- 请将 `db.mysql.password` 修改为你的MySQL root密码
- 如果MySQL运行在其他服务器,请修改 `db.mysql.host`

## 4. 更新Maven依赖

已添加以下依赖到 `pom.xml`:

```xml
<!-- MySQL驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.2.0</version>
</dependency>

<!-- HikariCP - 数据库连接池 -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

执行以下命令下载依赖:
```bash
mvn clean install
```

## 5. 运行测试

### 启动应用程序

```bash
mvn compile exec:java -Dexec.mainClass="com.lux032.musicautotagger.Main"
```

### 查看日志记录

在MySQL中查询已处理的文件:

```sql
USE music_demo;

-- 查看所有已处理文件
SELECT * FROM processed_files ORDER BY processed_time DESC;

-- 查看最近24小时处理的文件
SELECT * FROM recent_processed_files;

-- 查看统计信息
SELECT * FROM processing_statistics;

-- 按艺术家统计
SELECT artist, COUNT(*) as file_count 
FROM processed_files 
GROUP BY artist 
ORDER BY file_count DESC;

-- 按专辑统计
SELECT album, COUNT(*) as file_count 
FROM processed_files 
GROUP BY album 
ORDER BY file_count DESC;
```

## 6. 数据库表结构

### processed_files 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 自增主键 |
| file_hash | VARCHAR(64) | 文件哈希值(唯一) |
| file_name | VARCHAR(500) | 文件名 |
| file_path | VARCHAR(1000) | 文件完整路径 |
| file_size | BIGINT | 文件大小(字节) |
| processed_time | DATETIME | 处理时间 |
| recording_id | VARCHAR(100) | MusicBrainz录音ID |
| artist | VARCHAR(500) | 艺术家 |
| title | VARCHAR(500) | 曲目标题 |
| album | VARCHAR(500) | 专辑名称 |
| created_at | TIMESTAMP | 记录创建时间 |
| updated_at | TIMESTAMP | 记录更新时间 |

### 索引

- `idx_file_hash` - 文件哈希索引(快速查重)
- `idx_processed_time` - 处理时间索引(时间范围查询)
- `idx_recording_id` - 录音ID索引
- `idx_artist` - 艺术家索引(搜索和统计)
- `idx_title` - 标题索引
- `idx_album` - 专辑索引

## 7. 常见问题

### Q1: 连接数据库失败

**错误信息:** `Unable to connect to database`

**解决方案:**
1. 检查MySQL服务是否启动
2. 验证用户名和密码是否正确
3. 确认数据库 `music_demo` 已创建
4. 检查防火墙是否阻止了3306端口

### Q2: 找不到表

**错误信息:** `Table 'music_demo.processed_files' doesn't exist`

**解决方案:**
程序会自动创建表结构。如果未自动创建,请手动执行:
```bash
mysql -u root -p music_demo < src/main/resources/schema.sql
```

### Q3: 字符编码问题

**问题:** 中文乱码

**解决方案:**
确保MySQL配置使用UTF-8编码:
```sql
-- 查看数据库编码
SHOW CREATE DATABASE music_demo;

-- 如果不是utf8mb4,重新创建
DROP DATABASE music_demo;
CREATE DATABASE music_demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

## 8. 性能优化建议

1. **定期清理旧记录**
   ```sql
   -- 删除90天前的记录
   DELETE FROM processed_files WHERE processed_time < DATE_SUB(NOW(), INTERVAL 90 DAY);
   ```

2. **优化表**
   ```sql
   OPTIMIZE TABLE processed_files;
   ```

3. **监控连接池**
   - 根据实际并发量调整 `maxPoolSize`
   - 监控数据库连接数: `SHOW PROCESSLIST;`

## 9. 数据备份

### 备份数据库

```bash
mysqldump -u root -p music_demo > backup_$(date +%Y%m%d).sql
```

### 恢复数据库

```bash
mysql -u root -p music_demo < backup_20231201.sql
```

## 10. 从文本日志迁移

如果你之前使用文本日志,可以编写脚本导入到MySQL:

```java
// 读取旧的文本日志文件
// 解析每一行
// 插入到MySQL数据库
```

完整的迁移工具可以根据需要开发。

---

**技术支持:** 如有问题请查看项目文档或提交Issue
