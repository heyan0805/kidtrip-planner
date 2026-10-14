package com.hellen.kidtrip.flyai;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 调飞猪 CLI 的唯一入口。上层（Controller / 后面的检索编排）只会拿到 stdout 上的字符串，
 * 完全看不见"底下起了一个 Node 进程"这件事。
 *
 * 为什么是子进程而不是 HTTP：
 *   飞猪这个 skill 的交付形态就是一个本地命令行工具（npm 包 @fly-ai/flyai-cli），
 *   它往外吐 JSON 的方式是"往 stdout 打一行"。它底下的 MCP 端点
 *   （https://flyai.open.fliggy.com/mcp）要内置私有密钥 + 请求签名 + x-ff-ctx 上下文头，
 *   官方没公开，实测直连 401。所以唯一可靠的路就是把它当子进程调。
 *
 * 这个类里藏了三个"看起来能跑但迟早会炸"的点，每个都写了原因。
 * 其中编码那一条，我实测后发现和我一开始说的不一样，注释里写的是实测结论。
 */
@Component
public class FlyaiRunner {

    private final FlyaiProperties props;

    public FlyaiRunner(FlyaiProperties props) {
        this.props = props;
    }

    /**
     * 把上层给的参数补全成"实际要执行的完整命令"。
     *
     * 单独抽出来，是为了让探针接口能把**真实发出去的命令**原样回显给你看 ——
     * 排障时最常见的误判就是"我以为我查的是 A"，把命令摊开，一眼可判。
     */
    public List<String> describe(List<String> flyaiArgs) {
        List<String> command = new ArrayList<>();
        command.add(props.nodePath());
        command.add(props.cliPath());
        command.addAll(flyaiArgs);
        return command;
    }

    /**
     * 跑一次飞猪 CLI，返回 stdout 的原始文本（正常是一个 JSON 对象）。
     *
     * @param flyaiArgs 传给 CLI 的参数列表，第一个元素是子命令。例如
     *                  List.of("search-poi", "--city-name", "深圳", "--category", "动物园")
     * @throws FlyaiException 起不来、超时、退出码非 0、输出为空 —— 四种失败都翻译成人话
     */
    public String run(List<String> flyaiArgs) {
        List<String> command = describe(flyaiArgs);

        Process process;
        try {
            // 为什么用 ProcessBuilder(List) 而不是 Runtime.exec("node flyai ... 深圳")：
            // 数组形式**不经过任何 shell**。命令行的解析、引号、转义全部不存在，
            // 所以 "深圳" 这种中文、带空格的酒店名，都不可能被切碎。
            // 一旦拼成一个字符串，Windows 的解析器就会拿空格去切它 —— 而且是静默地切错。
            //
            // 为什么不直接执行 flyai（那个 .cmd）：
            // npm 在 Windows 上给 CLI 装的是个 .cmd 批处理 shim，Java 起不了它，
            // 得绕 cmd.exe /c，参数又要被 cmd 解析第二遍 —— 多一层就多一个踩坑面。
            // 直接让 node 跑 bundle.cjs，参数从 Java 直达 node，中间没有任何解释器。
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new FlyaiException(FlyaiException.Reason.LAUNCH,
                    "启动飞猪查询进程失败：" + e.getMessage()
                    + "（检查 kidtrip.flyai.node-path / cli-path）");
        }

        // 两个流必须并发读，这是实测过的死锁条件：
        // 让子进程往 stderr 灌 200KB，父进程"先读完 stdout 再读 stderr" 会永远卡住
        // （顺序读 10 秒后仍未读完；改成并发读 299ms 读完）。
        // 原理：stdout 和 stderr 各自是一个容量有限的管道（Windows 上几 KB 到几十 KB）。
        // 我们不去读 stderr，它的缓冲区就会满；子进程阻塞在"写 stderr"上，就再也不会写 stdout 也不会退出；
        // 而我们在等 stdout 的 EOF —— 两边互等，谁都不动。
        // 用虚拟线程而不是普通线程：这里做的事纯粹是"阻塞等 I/O"，正是虚拟线程的适用场景，创建成本极低。
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outPump = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread errPump = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));

        boolean finished;
        try {
            // 超时必须由 waitFor 来管。
            // 反面教材：如果改成在主线程里先 readAllBytes() 再 waitFor，
            // 那么主线程会一直阻塞在"等 stdout 关闭"上 —— 子进程不退出，stdout 就永远不关闭，
            // 于是这个 waitFor 永远轮不到执行，超时形同虚设。
            finished = process.waitFor(props.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();  // 复原中断标志，别把它吞掉（吞掉会让上层框架的上层收不到信号）
            throw new FlyaiException(FlyaiException.Reason.INTERRUPTED, "飞猪查询被中断");
        }

        if (!finished) {
            // 强杀之后，两个读线程会立刻收到 EOF 并正常结束，不会泄漏。
            process.destroyForcibly();
            throw new FlyaiException(FlyaiException.Reason.TIMEOUT,
                    "飞猪查询超时（" + props.timeoutSeconds() + " 秒），请稍后重试");
        }

        join(outPump);
        join(errPump);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            // 飞猪 CLI 的约定：成功走 stdout，失败走 stderr 并给非 0 退出码。
            // 实测（故意传错密钥）：stderr 是 "MCP HTTP 401 ... Invalid API key"，退出码 1，stdout 空。
            String detail = err.toString().isBlank() ? "（无错误输出）" : tail(err.toString());
            // 2026-10-13 实测补上的一类失败：上游有限流与风控。
            //   并发 6 个 → 3 个 "MCP HTTP 429 Rate limit exceeded"
            //   连续串行 6 个不间隔 → 后 3 个 "MCP HTTP 451 Abnormal access behavior detected by risk control"
            // 这两种失败得**等一会儿再试**，和"参数写错"是完全不同的处置，所以在抛出点就分开。
            // 判据是从 stderr 里找状态码 —— 不优雅，但它是这条链上唯一能拿到的信息（CLI 不吐结构化错误）。
            if (detail.contains("429") || detail.contains("451")) {
                throw new FlyaiException(FlyaiException.Reason.RATE_LIMITED,
                        "飞猪接口限流（" + detail + "）");
            }
            throw new FlyaiException("飞猪查询失败（退出码 " + exitCode + "）：" + detail);
        }

        String stdout = out.toString();
        if (stdout.isBlank()) {
            // 退出码 0 却什么都没吐 —— 属于异常情况，宁可报错也不要往下传一个空字符串。
            throw new FlyaiException(FlyaiException.Reason.EMPTY, "飞猪查询没有返回内容");
        }
        return stdout;
    }

    /**
     * 把整个流读干。
     *
     * 显式指定 UTF-8，而不是用 new String(bytes) 走默认字符集 —— 但这里要诚实地补一句：
     * 在 **Java 18 及以上**（我们用 21），默认字符集**已经是 UTF-8 了**（JEP 400），
     * 所以"不指定就会按 Windows 的 GBK 解出乱码"这个老说法，在这个版本上**不成立**。
     * 我实测过：两种写法得到的字符串完全相等，而且含中文的参数能正确传进 CLI、返回的中文也能正确读回。
     *
     * 那为什么还显式写？——因为它买的是"确定性"，而不是"修复"：
     *   ① 如果哪天有人给 JVM 加了 -Dfile.encoding=GBK，或者把 JDK 降回 17，这行就是唯一的防线；
     *   ② 它把"这里期望 UTF-8"写进了代码，读代码的人不用再去想"默认是什么"；
     *   ③ 代价为零。
     * 这就是"防御性写法"和"迷信"的区别：知道自己防的是什么，也知道它现在没在救你的命。
     */
    private static void drain(InputStream in, StringBuilder sink) {
        try (in) {
            sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 进程被强杀时读流会抛异常。这里是**故意**吞掉的：
            // 真正的原因（超时/退出码）在调用方已经被翻译成人话了，这里再抛只会把它盖掉。
        }
    }

    private static void join(Thread t) {
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 错误信息可能很长，只留尾巴 —— 真正的原因通常在最后一行。 */
    private static String tail(String s) {
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 300 ? t : "…" + t.substring(t.length() - 300);
    }
}
