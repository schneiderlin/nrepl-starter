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

### 使用 Java Agent (推荐 - 无需修改代码)

使用 Java Agent 可以自动启动 nrepl server，无需修改 main 函数。Agent 会在 JVM 启动时自动运行，无需任何代码修改。

#### 构建 agent jar

首先需要构建项目以生成 agent jar:

```bash
mvn clean package
```

构建完成后，在 `target` 目录下会生成 `nrepl-starter-2.0.0-SNAPSHOT-agent.jar` 文件（包含所有依赖的 fat jar）。

#### 使用 agent

在启动应用时添加 `-javaagent` 参数:

```bash
java -javaagent:./target/nrepl-starter-2.0.0-SNAPSHOT-agent.jar -jar your-app.jar
```

或者在 IDE 中配置 VM options (Run Configuration):
```
-javaagent:path/to/nrepl-starter-2.0.0-SNAPSHOT-agent.jar
```

#### 方式二: 从 Maven 仓库获取 agent jar

如果 agent jar 已发布到 Maven 仓库，可以通过 Maven 插件下载:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-agent</id>
            <phase>package</phase>
            <goals>
                <goal>copy</goal>
            </goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>io.github.schneiderlin</groupId>
                        <artifactId>nrepl-starter</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                        <classifier>agent</classifier>
                        <type>jar</type>
                    </artifactItem>
                </artifactItems>
                <outputDirectory>${project.build.directory}</outputDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### 配置选项

通过系统属性配置 agent:

- `-Dnrepl.port=7888` - 设置 nrepl 端口 (默认: 7888)
- `-Dnrepl.enabled=true` - 启用/禁用 nrepl (默认: true)

示例:
```bash
java -javaagent:./nrepl-starter-2.0.0-SNAPSHOT-agent.jar \
     -Dnrepl.port=9999 \
     -Dnrepl.enabled=true \
     -jar your-app.jar
```

### main 函数中使用 (手动方式)
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

## 示例项目

项目包含一个完整的示例项目 (`examples/basic`)，演示如何使用 nrepl-starter。

### 构建示例项目

首先需要构建主项目（因为示例依赖 SNAPSHOT 版本）:

```bash
# 在项目根目录执行
mvn clean install
```

然后进入示例项目目录:

```bash
cd examples/basic
mvn clean package
```

### 运行示例项目

示例项目提供了两种运行方式：

#### 方式一: 使用 Java Agent (推荐)

使用 agent 方式无需修改代码。首先需要确保主项目已构建并生成了 agent jar:

```bash
# 在项目根目录执行
mvn clean package
```

然后运行示例项目:

**推荐: 使用 IDE 运行**

1. 在 IDE (IntelliJ IDEA / Eclipse) 中打开 `examples/basic` 项目
2. 配置 Run Configuration:
   - Main class: `com.example.App`
   - VM options: `-javaagent:../../target/nrepl-starter-2.0.0-SNAPSHOT-agent.jar`
3. 运行即可

**或者使用命令行 (使用 java -jar):**

```bash
cd examples/basic

# 1. 先构建项目 (生成可执行 jar)
mvn clean package

# 2. 使用手动方式运行 (使用 App.java 中已有的代码)
java -jar target/basic-1.0-SNAPSHOT.jar

# 3. 使用 agent 方式运行:
#    先注释掉 App.java 中第 19-21 行的手动启动代码，然后重新打包
mvn clean package

java -javaagent:../../target/nrepl-starter-2.0.0-SNAPSHOT-agent.jar -jar target/basic-1.0-SNAPSHOT.jar
```

**注意**: 如果使用 agent 方式，需要先注释掉 `App.java` 中手动启动 nrepl 的代码（第 19-21 行），避免重复启动。

#### 方式二: 手动方式 (当前代码已包含)

示例项目的 `App.java` 已经包含了手动启动 nrepl 的代码，可以直接运行:

```bash
# 从 examples/basic 目录运行
mvn clean package
java -jar target/basic-1.0-SNAPSHOT.jar
```

或者在 IDE 中直接运行 `com.example.App` 的 main 方法。

### 连接 Clojure REPL

启动应用后，nrepl server 会在 7888 端口监听。

#### 使用 VSCode + Calva

1. 打开 VSCode，安装 Calva 插件
2. 打开 `examples/basic` 目录
3. 使用命令面板 (Ctrl+Shift+P / Cmd+Shift+P) 选择 "Calva: Connect to a Running REPL Server"
4. Project type 选择 `generic`
5. 输入端口号 `7888`
6. 连接成功后，可以在 REPL 中调用 Java 方法

#### 使用 Clojure REPL 调用 Java 方法

连接成功后，可以在 REPL 中执行以下代码（参考 `src/repl/core.clj`）:

```clojure
;; 导入 Java 类
(ns repl.core
  (:import [com.example App]))

;; 调用静态方法
(App/add 1 2)
;; => 3

;; 创建实例
(def app (App.))

;; 调用实例方法
(.subtract app 10 3)
;; => 7
```

### 示例项目结构

```
examples/basic/
├── pom.xml                    # Maven 配置，包含 nrepl-starter 依赖
├── src/
│   ├── main/
│   │   └── java/
│   │       └── com/example/
│   │           └── App.java   # 示例 Java 类，包含可调用的方法
│   └── repl/
│       └── core.clj           # Clojure REPL 示例代码
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
