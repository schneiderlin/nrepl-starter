package repl;

import clojure.lang.RT;
import clojure.lang.Var;
import repl.config.StarterServiceProperties;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@SuppressWarnings({"all"})
public class R {
    private static final Var EVAL = var("eval");
    private static final Var READ_STRING = var("read-string");

    private static final Map<String, Object> vars = new HashMap<>();


    private StarterServiceProperties starterServiceProperties;

    public R(StarterServiceProperties properties) {
        starterServiceProperties = properties;
    }

    private static Var var(String varName) {
        return RT.var("clojure.core", varName);
    }

    public Thread start(int port) {
        Thread replThread = new Thread(() -> {
            // embedded Clojure creates `user` without clojure.core referred;
            // refer it so nrepl clients land in a normal REPL namespace
            eval("(clojure.core/binding [clojure.core/*ns* (clojure.core/create-ns (quote user))]"
                    + " (clojure.core/refer (quote clojure.core)))");
            eval("(require '[nrepl.server :refer [start-server]])");
            eval("(require '[cider.nrepl :refer (cider-nrepl-handler)])");
            eval("(def repl-server (start-server :port " + port + " :handler cider-nrepl-handler))");
            try {
                eval("(require 'repl.tools 'repl.inspect 'repl.invoke)");
                System.out.println("[nrepl-starter] repl.tools + repl.inspect + repl.invoke loaded: compile-and-load!, redefine!, memory, thread-dump, ... available over nrepl");
            } catch (Throwable t) {
                System.err.println("[nrepl-starter] failed to load repl.tools/repl.inspect: " + t);
            }
            if (Boolean.parseBoolean(System.getProperty("nrepl.dashboard.enabled", "true"))) {
                try {
                    eval("(require 'repl.dashboard)");
                    eval("(repl.dashboard/start!)");
                } catch (Throwable t) {
                    // e.g. dashboard deps excluded, or target JVM < 17 (Jetty 12)
                    System.err.println("[nrepl-starter] dashboard failed to start: " + t);
                }
            }
        });
        replThread.setName("Nrepl-Service");
        replThread.start();
        return replThread;
    }

    public static void setVar(String varName, Object value) {
        vars.put(varName, value);
    }

    private static <T> T eval(String... code) {
        return (T) EVAL.invoke(readString(String.join("\n", code)));
    }

    private static <T> T readString(String s) {
        return (T) READ_STRING.invoke(s);
    }
}
