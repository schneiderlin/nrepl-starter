## 2.0 版本

> 我的同事 lgp 哥接手维护了这个项目. https://github.com/lgp547/any-door-plugin. 核心功能(idea方法调用)没有区别, anydoor 的更新和维护更频繁, 使用体验更流畅, 建议使用 anydoor.   
本项目会继续维护, 更专注于 clojure nrepl 的使用场景而不是 idea 插件


## 调用项目内方法 节约大量开发时间 提高效率 已有IDEA 插件

### 添加依赖
在需要使用的项目中完成下面操作

- maven
直接在项目的`pom.xml`里引入依赖 记得刷新maven
```xml
<dependency>
    <groupId>io.github.schneiderlin</groupId>
    <artifactId>nrepl-starter</artifactId>
    <version>2.0.0-SNAPSHOT</version>
</dependency>
```
- gradle 记得刷新gradle
```groovy
implementation 'io.github.schneiderlin:nrepl-starter:1.0.9'
```

### main 函数中使用
```java
 public static void main(String[] args) {
    // ----------- 添加 nrepl server --------------------
    R r = new R(new StarterServiceProperties());
    Thread replThread = r.start(7888);
    System.out.println("Hello World!");

    Thread workerThread = new Thread(() -> {
        // This thread will run indefinitely or until its task is complete
        while (true) {
            try {
                Thread.sleep(1000); // Simulate work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                break;
            }
        }
    });
    workerThread.start();
        // --------------------------------------------------


    // 其他业务代码
}
```

## 使用 clojure
### prerequisite
本地安装 clojure
https://clojure.org/guides/install_clojure  
安装 vscode, 和 vscode 插件 calva

### vscode 连接项目
启动 spring 项目, PlutusApplication.main.  
启动完成后, 7888 端口会有一个 clojure nrepl 进程在监听.  
在 vscode 使用 calva 插件的 connect to a running REPL server  
![img.png](doc/img.png)  
project type 选择 generic  
![img_1.png](doc/img_1.png)  
输入端口号 7888   
![img_2.png](doc/img_2.png)
