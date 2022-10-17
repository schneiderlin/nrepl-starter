package repl;

import clojure.lang.RT;
import clojure.lang.Var;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import repl.config.StarterServiceProperties;

import java.util.Arrays;
import java.util.List;

@Service
@SuppressWarnings({"all"})
public class ReplClient {
    private static final Var EVAL = var("eval");
    private static final Var READ_STRING = var("read-string");

    private static Var var(String varName) {
        return RT.var("clojure.core", varName);
    }

    public static Object evalClient(String code) {
        return eval("(eval-client " + "\"" + code.replace("\"", "\\\"") + "\"" + ")");
    }

    public void start(int port) {
        Thread replThread = new Thread(() -> {
            eval("(require '[nrepl.core :as nrepl])");
            eval("(def conn (nrepl/connect :port " + port + "))");
            eval("(def timeout 3000)");
            eval("(def client (nrepl/client conn timeout))");
            eval("(defn eval-client [code]\n" +
                    "     (let [msg (nrepl/message client {:op \"eval\" :code code})\n" +
                    "           values (nrepl/response-values msg)]\n" +
                    "       (first values)))");
        });
        replThread.setName("Nrepl-Service-Client");
        replThread.start();
    }

    private static <T> T eval(String... code) {
        return (T) EVAL.invoke(readString(String.join("\n", code)));
    }

    private static <T> T readString(String s) {
        return (T) READ_STRING.invoke(s);
    }
}
