import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 反证实验（自带超时，不靠外部杀进程）：
 * 子进程先往 stderr 灌 200KB，再往 stdout 写一小段。
 * 父进程"顺序读"：先死等 stdout，再去读 stderr。
 * 判定：给 stdout 10 秒，看它能不能读完。
 */
public class Probe2 {

    static byte[] readAll(InputStream in) {
        try (in) { return in.readAllBytes(); } catch (Exception e) { return new byte[0]; }
    }

    public static void main(String[] args) throws Exception {
        String js = "const e='x'.repeat(200000);process.stderr.write(e);process.stdout.write('OK')";

        System.out.println("[顺序读] 只读 stdout，10 秒上限...");
        {
            Process p = new ProcessBuilder("node", "-e", js).start();
            AtomicInteger len = new AtomicInteger(-1);
            Thread t = new Thread(() -> len.set(readAll(p.getInputStream()).length));
            t.setDaemon(true);
            long t0 = System.currentTimeMillis();
            t.start();
            t.join(10_000);
            System.out.println("  stdout 读完?      " + !t.isAlive());
            System.out.println("  已读到字节        " + len.get());
            System.out.println("  耗时 ms           " + (System.currentTimeMillis() - t0));
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }

        System.out.println();
        System.out.println("[并发读] stdout / stderr 各一个线程，10 秒上限...");
        {
            Process p = new ProcessBuilder("node", "-e", js).start();
            AtomicInteger o = new AtomicInteger(-1), e = new AtomicInteger(-1);
            Thread t1 = new Thread(() -> o.set(readAll(p.getInputStream()).length));
            Thread t2 = new Thread(() -> e.set(readAll(p.getErrorStream()).length));
            t1.setDaemon(true); t2.setDaemon(true);
            long t0 = System.currentTimeMillis();
            t1.start(); t2.start();
            t1.join(10_000); t2.join(10_000);
            System.out.println("  两个流都读完?     " + (!t1.isAlive() && !t2.isAlive()));
            System.out.println("  stdout 字节       " + o.get());
            System.out.println("  stderr 字节       " + e.get());
            System.out.println("  耗时 ms           " + (System.currentTimeMillis() - t0));
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
