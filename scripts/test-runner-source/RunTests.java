import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小 JUnit 风格运行器：在普通 JVM 里反射执行 {@code @Test} 方法。
 *
 * 为什么需要它：
 *   1) 本机 Gradle test worker 加载 sqlite-jdbc 原生库会抛
 *      {@code UnsatisfiedLinkError: 'void org.sqlite.core.NativeDB._open_utf8(byte[], int)'}；
 *      已用 git worktree 在 HEAD 2947332 的干净代码上复现同样的 24/24 全挂，
 *      而同一个 jar 在普通 JVM 里 sqlite 完全正常 —— 属于环境问题，与仓库代码无关。
 *   2) 本机 Gradle 缓存里没有 junit-platform-launcher，无法使用 Launcher API。
 *
 * 本项目全部用例都是无参 {@code @Test}，基类 {@code DbTestSupport} 的
 * {@code @BeforeEach} 负责重置临时库，因此反射调用与 JUnit 语义等价。
 */
public class RunTests {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        List<String> failures = new ArrayList<>();
        for (String cn : args) {
            Class<?> c = Class.forName(cn);
            List<Method> tests = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (hasAnno(m, "org.junit.jupiter.api.Test")) tests.add(m);
            }
            tests.sort((a, b) -> a.getName().compareTo(b.getName()));
            System.out.println("\n== " + cn + " (" + tests.size() + " tests) ==");
            for (Method m : tests) {
                java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object inst = ctor.newInstance();
                try {
                    invokeAnnotated(c, inst, "org.junit.jupiter.api.BeforeEach");
                    m.setAccessible(true);
                    m.invoke(inst);
                    invokeAnnotated(c, inst, "org.junit.jupiter.api.AfterEach");
                    pass++;
                    System.out.println("  [PASS] " + m.getName());
                } catch (InvocationTargetException e) {
                    fail++;
                    Throwable t = e.getTargetException();
                    failures.add(cn + "." + m.getName() + " -> " + t);
                    System.out.println("  [FAIL] " + m.getName() + " -> " + t);
                }
            }
        }
        System.out.println("\nRESULT tests=" + (pass + fail) + " succeeded=" + pass + " failed=" + fail);
        for (String f : failures) System.out.println("  FAILED: " + f);
        System.exit(fail > 0 ? 1 : 0);
    }

    private static boolean hasAnno(Method m, String anno) {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            if (a.annotationType().getName().equals(anno)) return true;
        }
        return false;
    }

    private static void invokeAnnotated(Class<?> c, Object inst, String anno) throws Exception {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (hasAnno(m, anno)) {
                    m.setAccessible(true);
                    m.invoke(inst);
                }
            }
        }
    }
}
