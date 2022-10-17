## 使用方法
### prerequisite
本地安装 clojure
https://clojure.org/guides/install_clojure  
安装 vscode, 和 vscode 插件 calva

### 添加依赖

- maven
直接在项目的`pom.xml`里引入依赖
```xml
<dependency>
    <groupId>io.linzihao</groupId>
    <artifactId>nrepl-starter</artifactId>
    <version>1.0.6-SNAPSHOT</version>
</dependency>
```
- gradle
```groovy
implementation 'io.linzihao:nrepl-starter:1.0.6-SNAPSHOT'
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