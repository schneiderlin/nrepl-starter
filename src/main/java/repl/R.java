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
            eval("(require '[nrepl.server :refer [start-server]])");
            eval("(require '[cider.nrepl :refer (cider-nrepl-handler)])");
            eval("(def repl-server (start-server :port " + port + " :handler cider-nrepl-handler))");
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
