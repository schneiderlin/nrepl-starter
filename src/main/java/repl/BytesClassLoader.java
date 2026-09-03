package repl;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Child-first classloader over a shared name -&gt; bytecode registry.
 *
 * repl.tools keeps a cumulative registry of every class it compiled. Each
 * compile-and-load! creates a fresh BytesClassLoader, so previously defined
 * classes are re-read from the registry and cross-references between
 * agent-compiled classes always resolve to the latest bytecode. Classes not
 * in the registry delegate to the parent (the app's classloader).
 */
public class BytesClassLoader extends ClassLoader {
    private final ConcurrentHashMap<String, byte[]> registry;

    public BytesClassLoader(ClassLoader parent, ConcurrentHashMap<String, byte[]> registry) {
        super(parent);
        this.registry = registry;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = registry.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null && registry.containsKey(name)) {
                c = findClass(name); // child-first, but only for registry classes
            }
            if (c == null) {
                c = super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }
}
