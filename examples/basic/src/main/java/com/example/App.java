package com.example;

// import repl.R;
// import repl.config.StarterServiceProperties;

/**
 * Hello world!
 */
public class App {
    public static int add(int a, int b) {
        return a + b;
    }

    public int subtract(int a, int b) {
        return a - b;
    }

    public static void main(String[] args) throws InterruptedException {
        // 手动方式: 不使用 -javaagent 时，取消下面注释自行启动 nrepl
        // R r = new R(new StarterServiceProperties());
        // Thread replThread = r.start(7888);

        System.out.println("App running. add(1, 2) = " + add(1, 2));

        // keep the JVM alive so you can connect over nrepl and hot-swap code
        Thread.currentThread().join();
    }
}
