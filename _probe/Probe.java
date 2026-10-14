import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 一次性探查：不打印中文（避免控制台编码干扰），只打印 ASCII 事实。
 * 中文一律用 \\uXXXX 转义，绕开源码文件本身的编码问题。
 */
public class Probe {

    public static void main(String[] args) throws Exception {
        String city = "\u6DF1\u5733";        // 深圳
        String zoo = "\u52A8\u7269\u56ED";   // 动物园
        String happy = "\u6B22\u4E50\u8C37"; // 欢乐谷

        System.out.println("== 1. JVM 字符集事实 ==");
        System.out.println("Code.defaultCharset()   = " + Charset.defaultCharset());
        System.out.println("file.encoding           = " + System.getProperty("file.encoding"));
        System.out.println("native.encoding         = " + System.getProperty("native.encoding"));
        System.out.println("stdout.encoding         = " + System.getProperty("stdout.encoding"));
        System.out.println("sun.jnu.encoding        = " + System.getProperty("sun.jnu.encoding"));

        System.out.println();
        System.out.println("== 2. 子进程吐中文，两种读法是否一致 ==");
        ProcessBuilder pb = new ProcessBuilder("node", "-e",
                "process.stdout.write('" + city + happy + "')");
        Process p = pb.start();
        byte[] raw = p.getInputStream().readAllBytes();
        p.waitFor(10, TimeUnit.SECONDS);
        String naive = new String(raw);
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        System.out.println("byte 数                = " + raw.length);
        System.out.println("naive.equals(utf8)     = " + naive.equals(utf8) + "   <- true 就说明默认读法没踩坑");
        System.out.println("naive.length           = " + naive.length());
        System.out.println("utf8.length()          = " + utf8.length());
        System.out.println("utf8 含 '深圳'         = " + utf8.contains(city));

        System.out.println();
        System.out.println("== 3. Java 直接调 flyai CLI（参数是中文）==");
        List<String> cmd = List.of("node",
                System.getenv().getOrDefault("FLYAI_CLI",
                        System.getProperty("user.home")
                                + "/AppData/Roaming/npm/node_modules/@fly-ai/flyai-cli/dist/flyai-bundle.cjs"),
                "search-poi", "--city-name", city, "--category", zoo);
        System.out.println("命令元素个数           = " + cmd.size() + "（数组传参，不经过任何 shell）");
        ProcessBuilder pb2 = new ProcessBuilder(cmd);
        Process p2 = pb2.start();
        byte[] out2 = p2.getInputStream().readAllBytes();
        byte[] err2 = p2.getErrorStream().readAllBytes();
        boolean done = p2.waitFor(150, TimeUnit.SECONDS);
        String so = new String(out2, StandardCharsets.UTF_8);
        String se = new String(err2, StandardCharsets.UTF_8);
        System.out.println("是否在超时内结束       = " + done);
        System.out.println("退出码                 = " + (done ? p2.exitValue() : -1));
        System.out.println("stdout 字符数          = " + so.length());
        System.out.println("stderr 字符数          = " + se.length());
        System.out.println("stdout 是 JSON 对象    = " + so.stripLeading().startsWith("{"));
        System.out.println("stdout 含 itemList     = " + so.contains("itemList"));
        System.out.println("stdout 含 '深圳'       = " + so.contains(city) + "   <- 参数中文没被切碎、返回中文没乱码");

        System.out.println();
        System.out.println("== 4. 管道缓冲区死锁实验（顺序读 stdout 再读 stderr）==");
        // 让子进程先往 stderr 灌 200KB，再往 stdout 灌一小段。
        // 若我们先死等 stdout，子进程会被 stderr 管道塞满而卡住 -> 双方互等 = 死锁。
        Map<String, String> env = new HashMap<>();
        ProcessBuilder pb3 = new ProcessBuilder("node", "-e",
                "const e='x'.repeat(200000);process.stderr.write(e);process.stdout.write('{\"ok\":true}')");
        pb3.environment().putAll(env);
        Process p3 = pb3.start();
        Thread reader = new Thread(() -> {
            try { p3.getInputStream().readAllBytes(); } catch (Exception ignored) { }
        });
        reader.setDaemon(true);
        reader.start();  // 先起一个线程抽 stdout，再用当前线程抽 stderr —— 这就是"并发读"
        long t0 = System.currentTimeMillis();
        byte[] errBig = p3.getErrorStream().readAllBytes();
        boolean done3 = p3.waitFor(20, TimeUnit.SECONDS);
        System.out.println("子进程结束             = " + done3);
        System.out.println("stderr 字节数          = " + errBig.length);
        System.out.println("耗时 ms                = " + (System.currentTimeMillis() - t0));
        System.out.println("如果不并发读，这一步会永远卡在这里 —— 这就是 FlyaiRunner 必须开线程的原因");
    }
}
