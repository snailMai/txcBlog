# 第一次打包
## 后端
### 打包
1. 打包准备
```
1. pom.xml添加依赖
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
    </plugins>
</build>
2. 设置属性,以war文件为例
<groupId>com.test</groupId>
<artifactId>blog</artifactId>
<version>0.0.1-SNAPSHOT</version>
<packaging>war</packaging>
<name>blog</name>
<description>testm project for Spring Boot</description>
```
2. 生成包: 打包命令mvn clean package '-Dmaven.test.skip=true'
3. 在包目录下获得xxx.war文件

### 服务器准备
1. 打开服务器(linux)
2. 下载Tomcat: wget https://dlcdn.apache.org/tomcat/tomcat-10/v10.1.30/bin/apache-tomcat-10.1.30.tar.gz
3. 解压Tomcat: tar -xvzf apache-tomcat-10.1.30.tar.gz
4. (建议项, 非必选)移动Tomcat到/opt目录中: mv apache-tomcat-10.1.30 /opt/tomcat
5. 



# 复杂版
```
1.springboot项目部署到服务器的方法
看解决方法的直接下滑
第一步：先打包
1.1 jar包的方法
1.在IDEA中进行项目打包
IDEA调出maven工具栏；1.clean 2. install 就可以在target目标文件中看到自己想要的jar包。
mvn clean package -Dmaven.test.skip=true
2.war包的方式，部署到外部Tomact运行
1.在启动类里面需要添加一部分代码
public class TushuApplication extends SpringBootServletInitializer{
 //这是需要打成war包要加的
	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
		return application.sources(TushuApplication.class);
	}
 //这是springboot的启动类
	public static void main(String[] args) {
		SpringApplication.run(TushuApplication.class, args);
	}
 
}
2.修改pom文件
2.1.加上<packaging>war</packaging>定义打包的格式
2.2 去除POM文件中的tomcat依赖
<dependency>
   <groupId>org.springframework.boot</groupId>
   <artifactId>spring-boot-starter-web</artifactId>
   <!-- 移除嵌入式tomcat插件 -->
   <exclusions>
      <exclusion>
         <groupId>org.springframework.boot</groupId>
         <artifactId>spring-boot-starter-tomcat</artifactId>
      </exclusion>
   </exclusions>
</dependency>
或者
<dependency>
   <groupId>org.springframework.boot</groupId>
   <artifactId>spring-boot-starter-tomcat</artifactId>
   <scope>provided</scope>
</dependency>
将tomcat依赖作用域设置为provided
然后一样的用IDEA 自带的maven工具打包就好了，就会得到一个war包啦
2.将jar包或者war包发送到服务器
我看了很多人推荐用各种文件传输软件 进行文件传输，我想说闲的，下软件不浪费时间吗
直接桌面远程连接，复制本地电脑的文件，再到远程桌面指定文件夹下粘贴不就好了
Windows下的方法：

1.win键 – mstsc 打开远程控制应用（自带的）
2.输入你服务器的地址和密码，就出现你的远程桌面啦。然后就复制文件就可以了

3.服务器上部署项目
1.jar包：部署
1.打开命令行窗口，cd 你jar包的地址；来到你的jar包项目地址
2.执行命令：java -jar 项目名（jar包名字）.jar
注意：这种方法需要保证这个命令行窗口一直在，不能关了

2.war包：部署
把你上传的war包放到Tomcat的webapps目录里面，tomcat自动解压识别
注意：war包部署后的访问路径需要加上你的项目名
localhost:8080/Tomcat中的发布项目名/jiaowu/index
(PS:在Tomcat中的发布项目名中踩坑)
3.部署过程中遇见的问题
问题1：jar包打包失败；
解决方法：
在pom文件中添加以下配置
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-jar-plugin</artifactId>
    <version>2.4</version>
    <!-- 对要打的jar包进行配置 -->
    <configuration>
        <!-- Configuration of the archiver -->
        <archive>
            <!--生成的jar中，不要包含pom.xml和pom.properties这两个文件-->
            <addMavenDescriptor>false</addMavenDescriptor>

            <!-- Manifest specific configuration -->
            <manifest>
                <!--是否要把第三方jar放到manifest的classpath中-->
                <addClasspath>true</addClasspath>
                
                <!--生成的manifest中classpath的前缀，
                因为要把第三方jar放到lib目录下，
                所以classpath的前缀是lib/-->
                <classpathPrefix>lib/</classpathPrefix>
            </manifest>
        </archive>
        <!--过滤掉不希望包含在jar中的文件-->
        <excludes>
            <!-- 排除不需要的文件夹(路径是jar包内部的路径) -->
            <exclude>**/assembly/</exclude>
        </excludes>
    </configuration>
</plugin>
问题2.jar包打包成功，但是服务器启动时报错
报错原因：日志中提示：tomcat 错误或者freakmark 报错 或者 bean错误等待；
解决方法：（jar包冲突的原因）

1.打开pom文件的树状图
pom文件鼠标右键 看到有个树状图的图标点击 就可以看到项目依赖的jar包图啦。

2.查看是否有冲突的jar包
一些jar包的连线是红色的 把这些冲突的jar包排除掉（鼠标右键exclude即可）

3. 没有冲突包后重新 clean install 即可；
如果你还没有解决，那么还有一个办法，把maven中的jar包地址中的jar包全部删除，让项目重写下载jar包，这样打包出来就可以了。
最后希望可以帮助到你们！
```