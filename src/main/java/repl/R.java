package repl;

import clojure.lang.RT;
import clojure.lang.Var;
import com.alibaba.fastjson.JSON;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import repl.config.StarterServiceProperties;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Service
@SuppressWarnings({"all"})
public class R {
    private static Logger logger = LoggerFactory.getLogger(R.class);

    private static final Var EVAL = var("eval");
    private static final Var READ_STRING = var("read-string");

    private static final Map<String, Object> vars = new HashMap<>();

    public static ApplicationContext applicationContext;

    private StarterServiceProperties starterServiceProperties;
    private Environment environment;

    public R(StarterServiceProperties properties, ApplicationContext applicationContext, Environment env) {
        environment = env;
        starterServiceProperties = properties;
        repl.R.applicationContext = applicationContext;
    }

    private static Var var(String varName) {
        return RT.var("clojure.core", varName);
    }

    public static <T> T getBean(String name) {
        try {
            return (T) applicationContext.getBean(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static <T> T getBean(Class clazz) {
        try {
            return (T) applicationContext.getBean(clazz);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Object toJSONString(Object object) {
        return JSON.toJSONString(object);
    }

    public void init() {
        final List<String> mode = starterServiceProperties.getMode();
        final List<String> active = Arrays.asList(environment.getActiveProfiles());
        final boolean runMode = active.stream().anyMatch(mode::contains);
        if (starterServiceProperties.getState() && runMode) {
            start(starterServiceProperties.getPort());
        } else {
            logger.info("Clojure nrepl service is Loaded,but not start");
            logger.info("Clojure nrepl service running only on {} mode", mode);
        }
    }

    public void start(int port) {
        Thread replThread = new Thread(() -> {
            eval("(use '[clojure.tools.nrepl.server :only (start-server)])");
            eval("(use '[cider.nrepl :only (cider-nrepl-handler)])");
            eval("(def repl-server (start-server :port " + port + " :handler cider-nrepl-handler))");
            logger.info("Clojure nrepl is started on port(s): {} ", port);
        });
        replThread.setName("Nrepl-Service");
        replThread.start();
    }

    public static void setVar(String varName, Object value) {
        vars.put(varName, value);
    }

    public static void callMethod(Object instace, String methodName, List<String> argNames, List<Class> argClasses, String json) {
        try {
            Class<?> targetClass = AopUtils.isAopProxy(instace) ? AopUtils.getTargetClass(instace) : instace.getClass();
            Method method = targetClass.getDeclaredMethod(methodName, argClasses.toArray(new Class[0]));
            method.setAccessible(true);
            Gson gson = new Gson();

            Stream<Object> argValues;
            JsonElement jsonArgs = JsonParser.parseString(json);
            if (jsonArgs.isJsonNull()) {
                argValues = Stream.empty();
            } else {
                JsonObject args = jsonArgs.getAsJsonObject();
                argValues = IntStream.range(0, argNames.size())
                        .mapToObj(i -> {
                            String argName = argNames.get(i);
                            Class argClass = argClasses.get(i);
                            JsonElement arg = args.get(argName);
                            Optional<String> varOpt = getVar(arg);
                            if (varOpt.isPresent()) {
                                return vars.get(varOpt.get());
                            } else {
                                return gson.fromJson(arg, argClass);
                            }
                        });
            }

            Object result = method.invoke(instace, argValues.toArray());
            logger.info("Call method Result {}", result);
        } catch (Exception e) {
            logger.error("Call method Exception", e);
            throw new RuntimeException(e);
        }
    }

    private static Optional<String> getVar(JsonElement arg) {
        if (arg.isJsonPrimitive()) {
            JsonPrimitive argPrimitive = arg.getAsJsonPrimitive();
            if (argPrimitive.isString()) {
                String argString = argPrimitive.getAsString();
                if (argString.startsWith("$")) {
                    return Optional.of(argString.substring(1, argString.length()));
                }
            }
        }

        return Optional.empty();
    }

    private static <T> T eval(String... code) {
        return (T) EVAL.invoke(readString(String.join("\n", code)));
    }

    private static <T> T readString(String s) {
        return (T) READ_STRING.invoke(s);
    }
}
