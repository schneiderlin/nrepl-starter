package com.example;

import repl.R;
import repl.config.StarterServiceProperties;

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

    public static void main(String[] args) {
        // ----------- 添加 nrepl server --------------------
        // R r = new R(new StarterServiceProperties());
        // Thread replThread = r.start(7888);
        // System.out.println("Hello World!");

        // Thread workerThread = new Thread(() -> {
        //     // This thread will run indefinitely or until its task is complete
        //     while (true) {
        //         try {
        //             Thread.sleep(1000); // Simulate work
        //         } catch (InterruptedException e) {
        //             Thread.currentThread().interrupt(); // Restore interrupt status
        //             break;
        //         }
        //     }
        // });
        // workerThread.start();
         // --------------------------------------------------


        // 其他业务代码
    }
}
