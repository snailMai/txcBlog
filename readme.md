# 前后端启动
## 后端
1. 确认是本地还是服务端后, 修改对应值application修改spring.profiles.active
   1. 本地local
   2. 服务端blog
   3. 测试test
2. 确认后,修改对应application-xxx.properties
   1. spring.datasource.url
   2. spring.datasource.username
   3. spring.datasource.password
3. 注意不要不要将以上内容上传到git



# 问题记录
### 端口占用导致启动失败
判断方式:
以Windows 8080端口举例
命令提示符
1. netstat -ano | findstr 8080   -- 获得pid
2. tasklist | findstr {pid}  -- 可以看到程序
解决方式:

