# 执行步骤（1分钟完成）

## Step 1: 执行 SQL

```bash
cd backend/migrations
mysql -u root -p xiaozhi < schema.sql
```

输入 MySQL 密码后，脚本会自动执行并显示验证结果。

## Step 2: 重启服务

```bash
cd ..
./stop.sh
./run_bg.sh
```

## Step 3: 验证成功

```bash
# 方法 1: 查看日志
tail -f nohup.out | grep "内置技能"

# 应该看到：
# INFO - 成功注册 5 个内置技能

# 方法 2: 测试 API
curl http://localhost:8000/api/skills | jq
```

## 完成！✅

现在内置技能的提示词已存储在数据库中，可以随时修改无需重启服务。

**注意**：模型配置需要在 Android 设置中配置，系统不提供默认配置。
