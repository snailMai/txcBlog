# old
## 前后端版本

***
三个*是未处理的问题,处理后取消
***

### 框架
SpringBoot+Spring+SpringMVC+Mybatis
日志使用SpringBoot自带日志框架:SLF4J 结合LogBack

###windows
```
windows 10 1902之后版本:
IDEA 2020.1
maven3.3.9
tomcat 9.0.13
nodejs 14.16.1
    @vue/cli 4.5.12 (vue add axios(推荐使用npm install axios))

```


### linux

---
查看linux系统
- cat /etc/redhat-release 系统版本
- CentOS Linux release 7.5.1804 (Core)
---

```
linux:
uname -r 内核版本
3.10.0-1127.19.1.el7.x86_64

tomcat 9.0.43
mysql 5.7.24
```
创建testuser所用的wiki:
testuser:https://blog.csdn.net/tr1912/article/details/79371137


## 开发日志
### 4.10
application.properties 换成application.yaml  //还没打包

###4.11
1. tomcat占用
windows:cmd中查询端口netstat -ano | findstr {端口号}
2. 日志输出sql
mybatis.configuration.log-impl:org.apache.ibatis.logging.stdout.StdOutImpl

###4.12
开始弄前后端分离
1. 安装nodeJs
2. 建立第一个项目(还未成功运行)


###4.13
(时间太少..)
运行vue项目
IDEA上安装插件:vue.js

###4.14
[【2020版】4小时学会Spring Boot+Vue前后端分离](https://www.bilibili.com/video/BV137411B7vB?p=1)  
(【2020版】4小时学会Spring Boot+Vue前后端分离 第一讲)
1. 升级vue至3.0以上版本
大概是
npm uninstall vue
npm install @vue/cli -g
2. 界面创建vue项目
idea打开
vue add axios(前后端分离必备)
app.vue入口
config-index映射
views 前端界面
3. 成功运行及了解前端内容
.vue
html
js
css
4. 解决跨域
问题，springboot2.4高版本出现问题

###4.18
element UI学习

###4.19
忘了vue第一讲,思考vue运行流程图
结果见app

###4.20
element UI学习自动生成左侧列表aside


###4.21
(【2020版】4小时学会Spring Boot+Vue前后端分离 第三讲)
1. 解决菜单"套娃"情况,菜单会自动跳转网页
2. 菜单下的属性可跳转
3. 默认打开的菜单以及点击菜单才会高亮

###4.22
1. vue table组件和分页组件
2. Mybatis分页组件pageHelper,但未实际使用到

###4.25
- (【2020版】4小时学会Spring Boot+Vue前后端分离 第四讲)
- 前后端分页,除了少了分页插件自动生成个数,其余已经全部完成

###4.26
- (【2020版】4小时学会Spring Boot+Vue前后端分离 第五讲 21.46)
1. 表格方式 *fixed="right"*  可实现表格在右边
2. 前端传递参数和校验


###4.27
- (【2020版】4小时学会Spring Boot+Vue前后端分离 第五讲)
1. 新增testuser用户,前后端已经关联起来,也可以调用回调函数
2. 前端设置弹窗
3. 出现问题SqlSession [org.apache.ibatis.session.defaults.DefaultSqlSession@3afb527e] was not registered for synchronization because synchronization is not active


###4.28
1. org.mybatis.spring.MyBatisSystemException: nested exception is org.apache.ibatis.binding.BindingException: Parameter 'username' not found. Available parameters are [arg1, arg0, param1, param2]
    mapper接口中添加@Param
```
int insertTestUser(String username, Integer age);
变成
int insertTestUser(@Param("username")String username, @Param("age")Integer age);
```
2. org.apache.ibatis.binding.BindingException: Mapper method 'com.test.blog.testfortestuser.dao.TestUserMapper.insertTestUser attempted to return null from a method with a primitive return type (int).
将mapper返回的类型由int改为Integer


###4.29
1. 还是没有解决mybatis insert语句返回空的问题
> - insert语句返回的就是空,就没有返回Integer、Long、Boolean
> - (PS: 之前未报错的原因是：方法返回的TestUser对象，而insert返回的就是null，这个也可以属于TestUser对象)

###5.2
1. insert语句返回空的问题
```
// mapper.xml定义的问题
//原本
<select id = "insertTestUser">
//修改为
<insert id = "insertTestUser">
```
2. 各种小修改--删除TestUser以及前端自动获取TestUser个数


###5.3
- - (【2020版】4小时学会Spring Boot+Vue前后端分离 第六讲)
1. 前后端新增修改和删除

####遗留问题:
1. (可能导致卡顿情况)HikariPool-1 - Failed to validate connection com.mysql.cj.jdbc.ConnectionImpl@3f33c45a (No operations allowed after connection closed.). Possibly consider using a shorter maxLifetime value.
2. 前端注册TestUser,注册成功后跳转,注册TestUser还是显示蓝色
3. 前端 npm run serve 有两个warning


###5.4
####问题解决:
- 昨天:前端npm run serve 有两个warning,原因是menu.vue中v-for和v-if在同一个标签中使用
- 今天:$route.path==item2.path may cause unexpected type coercion  
解决方法:将==改成===  
问题在于==会先类型转换,再进行对比;===对比类型,也对比内容

1. 前端 app.vue 优化
2. 多条件查询(再也不用弄拼接sql这个坑货了...)



###5.5
1. 修改vue名称:TestDate => GetTestUser
2. vue前端使用全局变量
    ```$xslt
    // 全局变量
    由于用的比较多,直接使用全局变量
    创建全局变量文件
    import xxx from xxx
    Vue.prototype.{在其它vue使用的变量名} = xxx
    
    其它文件调用,this.{在其它vue使用的变量名}.{全局变量名称}
    
    // 局部变量
    import xxx from xxx
    xxx.{全局变量名称}
    ```

3. 解决vue报错：Failed to execute ‘open' on ‘XMLHttpRequest';  
在main.js中设置后端基址：axios.defaults.baseURL = 'http://127.0.0.1:8000'

4. 

5. vue前端报错Invalid prop: type check failed for prop “pagerSize“. Expected Number, got String

    ```$xslt
    原
    page-size="5"
    新
    :page-size="5"
    
    解释:在往子组件传值的时候，只要不是字符串类型，无论是不是变量，属性前面都必须加上冒号，不然传到子组件的时候统统都会变成字符串
    ```

6. 修改application-test.properties 为 application-test.yaml

7. 解决HikariPool-1 - Failed to validate connection com.mysql.cj.jdbc.ConnectionImpl@19085b69 (No operations allowed after connection closed.). Possibly consider using a shorter maxLifetime value.   
连接池连接线程未超过时长,导致连接报错  
解决方案:查看application-test.yaml中的Hakari配置

    
8. 解决菜单栏选中问题  
<el-menu router :default-active=$route.path> 替代 :class="$route.path===item2.path?'is-active':''"

    ```
    ***
    暂时解决vue中v-for和v-if和注册TestUser还是显示蓝色,遗留warning,记得解决
    ***
    ```

9. Centos7.5安装Nginx


###5.6
1. Centos7.5配置Nginx

2. 前端打包(vue.config.js)

3. 前端部署nginx,后端部署tomcat (nginx:8080, tomcat:8081)

遗留问题:
***
yaml文件后端打包报错:Failed to execute goal org.apache.maven.plugins:maven-resources-plugin:2.6:resources (default-resources) on project blog: Error loading property file 'D:\JAVA\workspace\realTXC\blog\src\main\resources\application-test.properties  
前端baseHost规避为:'http://129.226.174.57:8081/myBlog'  
需解决问题: url显示跟实际不符(虽然可用)
***


###5.7
解决不同电脑局域网共享问题
1. 右键文件->属性->共享->高级共享->共享此文件,确定
2. 网络共享中心->更改高级共享设置->启用两个
3. 找到该网络的ip,就可以在另一台电脑\\{ip}

###5.9
1. scp -P {remoteSSHPort} {fileName} {root@ip}:{remoteFilePath}
2. utf8和utf8mb4 utf8三字节,utf8mb4四字节,比如:emoji就用utf8mb4
3. 简单看了一下简历要求,以及更换新的简历模板

###5.10
更新简历模板,调整模板

###5.11
填写专业技能

###5.12
项目经历--设备发放

###5.13
修改部分九安经历,已经部分iotda

### 5.16
写完开发简历

### 5.17
熟悉docker

###5.18
解决windows 端口占用问题  
redis String和Hash

### 5.19
redis list,set 和  Sort set  
半个自我介绍


### 5.20
一些框架原理


### 5.24
1. Java六大设计原则
2. 事务
3. mysql函数


## 开始考虑blog
### 6.29
1. blog_user和blog接口和dto设计


### 6.30
1. blogUsermapper
2. mysql.sql


### 7.1
1. 写了一部分创建用户

## 2022
### 4.11
1. 解决删除失败的问题
2. 优化名称


### 4.12
1. 实现本地运行dist
2. 规避nginx缓存问题（使用无痕模式）
3. 编写python脚本-mvVue.py

### 4.13
1. 本地运行vue (其实是缓存导致的问题)

### 4.17
1. java修饰符:访问修饰符和非访问修饰符static\final\abstract\线程的两个
2. windows重置MySQL密码
3. java继承和多态(多态一般说的是两种,重写和重载(编译时多态和运行时多态))
4. 接口和抽象类
5. Sql语句选取每科前三名(并列方式,不并列的方式只能一个一个查)

### 4.20
1. java六大设计原则：单一，开闭，里氏替换，依赖倒置，接口隔离，迪米特
2. 简单工厂模式，工厂模式，单例模式
3. selenium三种等待：硬等待，显式等待和隐式等待
```asp
隐式等待：需要等待页面加载完成
显式等待，是针对于某个特定的元素设置的等待时间，每隔一段时间检测一次（默认0.5s）

显式等待超时：TimeoutException 
隐式等待超时：NoSuchElementException
```

### 4.21
1. ArrayList\LinkedList, hashmap\LinkedHashMap, hashset




# 2024
## 2024-09-17 001
已有文件优化
1. 接口拆分
2. 优化application修改
3. 新增修改日志

## 2024-09-17 002
1. 复制日志到现在
2. 小优化application

## 2024-09-22
1. 调整下get方法的路径顺序
2. 调整数据库默认值
3. 调整服务器日志位置