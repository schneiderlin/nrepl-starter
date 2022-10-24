## IDEA 插件
### 添加依赖

对当前项目执行 mvn install, 在本地 maven 仓库中安装

- maven
  直接在项目的`pom.xml`里引入依赖
```xml
<dependency>
    <groupId>io.github.schneiderlin</groupId>
    <artifactId>nrepl-starter</artifactId>
    <version>1.0.7-SNAPSHOT</version>
</dependency>
```
- gradle
```groovy
implementation 'io.github.schneiderlin:nrepl-starter:1.0.7-SNAPSHOT'
```

然后新增配置 start/src/main/resources/application-dev.yml
```yaml
clojure:
    nrepl:
        state: true
        port: 7888
        mode: dev
```

### 安装 idea 插件
在 https://github.com/schneiderlin/nrepl-starter/releases 中下载最新版本的插件压缩包.
拖动到 idea 中完成安装.

### 使用方式
先启动 spring 项目, 当看到控制台打印出
"Clojure nrepl is started on port(s): 7888" 时.
执行 idea 的 "Start REPL Client" 命令.
![img.png](img.png)

鼠标放到想要调用的方法内, command + enter, 选择 Call this method
![img_1.png](img_1.png)

使用演示视频参考 B 站 https://www.bilibili.com/video/BV1f44y1f7dt/

## 使用 clojure
### prerequisite
本地安装 clojure
https://clojure.org/guides/install_clojure  
安装 vscode, 和 vscode 插件 calva

### 添加依赖

对当前项目执行 mvn install, 在本地 maven 仓库中安装

- maven
直接在项目的`pom.xml`里引入依赖
```xml
<dependency>
    <groupId>io.github.schneiderlin</groupId>
    <artifactId>nrepl-starter</artifactId>
    <version>1.0.7-SNAPSHOT</version>
</dependency>
```
- gradle
```groovy
implementation 'io.github.schneiderlin:nrepl-starter:1.0.7-SNAPSHOT'
```

然后新增配置 start/src/main/resources/application-dev.yml
```yaml
clojure:
    nrepl:
        state: true
        port: 7888
        mode: dev
```

### vscode 连接项目
启动 spring 项目, PlutusApplication.main.  
启动完成后, 7888 端口会有一个 clojure nrepl 进程在监听.  
在 vscode 使用 calva 插件的 connect to a running REPL server  
![img.png](doc/img.png)  
project type 选择 generic  
![img_1.png](doc/img_1.png)  
输入端口号 7888   
![img_2.png](doc/img_2.png)
