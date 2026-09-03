package repl.agent;

import repl.R;
import repl.config.StarterServiceProperties;

import java.lang.instrument.Instrumentation;

/**
 * Java agent that automatically starts the nrepl server when the JVM starts.
 * 
 * Usage: Add -javaagent:path/to/nrepl-starter-agent.jar to your JVM arguments
 * 
 * Configuration via system properties:
 * - nrepl.port: Port number (default: 7888)
 * - nrepl.enabled: Enable/disable nrepl (default: true)
 */
public class NreplAgent {

    /**
     * Instrumentation handle captured at JVM startup. repl.tools reads this to
     * redefine already-loaded classes and inspect loaded classes / object sizes.
     * Stays null when nrepl is started manually via R (no -javaagent).
     */
    public static volatile Instrumentation instrumentation;

    private static volatile Thread replThread;

    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        startNrepl();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;
        startNrepl();
    }
    
    private static void startNrepl() {
        // Check if already started
        if (replThread != null && replThread.isAlive()) {
            return;
        }
        
        // Read configuration from system properties
        String portStr = System.getProperty("nrepl.port", "7888");
        String enabledStr = System.getProperty("nrepl.enabled", "true");
        
        boolean enabled = Boolean.parseBoolean(enabledStr);
        if (!enabled) {
            System.out.println("[NreplAgent] nrepl is disabled via system property");
            return;
        }
        
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            System.err.println("[NreplAgent] Invalid port number: " + portStr + ", using default 7888");
            port = 7888;
        }
        
        StarterServiceProperties properties = new StarterServiceProperties();
        properties.setPort(port);
        
        R r = new R(properties);
        replThread = r.start(port);
        
        System.out.println("[NreplAgent] nrepl server started on port " + port);
    }
}

