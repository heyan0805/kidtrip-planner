package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.llm.ChatComposer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * `POST /api/plan/chat` 的编排层 —— 和 PlanService 是一对。
 *
 * 为什么是两个 Service 而不是一个里的两个方法：
 *   它们编排的是**两条不同的流程**，依赖也不同（这条只用 ChatComposer，
 *   不用检索、不用规则、不用装配）。合成一个类的话，那个类会同时依赖
 *   RetrievalService + PlanComposer + ChatComposer + RetrievalPlanner，
 *   而任意一条流程的改动都要动这个类 —— 它就成了所有人都会去改的那个文件。
 *   **一个类该多大，看它有几种"变化的原因"，不看它有几个方法。**
 *
 * 它只做一件事：**把不合格的输入挡在模型之前**。
 * 判断"这句话是什么意思"是模型的事；判断"这次请求能不能成立"是这里的事。
 * 两者的界线是：**不需要理解语义就能判断的，归这里。**
 *
 * 参照工程的同一层把两个校验写在 Controller 里（`if (req.getMessages() == null ...)`），
 * 这里挪到 Service —— 因为写在 Controller 里就**测不了**（要起 MockMvc 才能覆盖到），
 * 而挪下来之后一个普通单测就能钉住它。这是 B4 那条"可测性是设计出来的"的又一次应用。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * 送给模型的历史消息条数上限（1 条 = 半轮）。
     *
     * 为什么是 24：一轮问答 = 2 条，24 条 = 12 轮。而把 7 个字段问清楚，
     * 每轮问 1~2 个，通常 4~8 轮就够 —— 12 轮足够覆盖"用户改主意、反复纠正"的情况。
     * 超出之后**从最早的开始丢**，因为对话的性质决定了最新几轮最相关。
     *
     * 说清代价：丢掉的早期信息**并没有真的消失** —— 它已经沉淀进 currentParams 了
     * （"想带孩子去珠海"这句话问过之后，city 就一直是"珠海"）。
     * 这正是"画像和对话历史分开存"带来的好处：**历史的长度不会影响已经提取出来的事实。**
     */
    private static final int MAX_HISTORY_MESSAGES = 24;

    /** 单条消息的长度上限。防止有人把一整篇文章贴进输入框，把 prompt 撑爆 */
    private static final int MAX_CONTENT_CHARS = 2000;

    private final ChatComposer composer;

    public ChatService(ChatComposer composer) {
        this.composer = composer;
    }

    public PlanChatData chat(PlanChatRequest request) {
        List<Message> messages = request.messagesOrEmpty();
        if (messages.isEmpty()) {
            // 抛 IllegalArgumentException 而不是自定义异常：GlobalExceptionHandler
            // 已经把它翻译成 4000「入参不合法」，message 原样给用户看。
            throw new IllegalArgumentException("messages 不能为空 —— 没有对话内容就没什么可回答的");
        }

        // 最后一条必须是用户说的。前端如果把历史整段发回来、却忘了带上本轮提问，
        // 我们会拿着"上一轮助手的话"再问模型一次 —— 它会回一句很像样的废话，
        // 而用户以为自己说的话发出去了。**这种"礼貌的答非所问"比直接报错难查得多**，
        // 所以宁可在这里挡住。
        Message last = messages.get(messages.size() - 1);
        if (!last.isUser()) {
            throw new IllegalArgumentException("最后一条消息必须来自用户 —— 你发的是一段以助手结尾的历史");
        }
        if (last.content().isBlank()) {
            throw new IllegalArgumentException("最后一条消息是空的 —— 没听到你问什么");
        }

        List<Message> trimmed = trim(messages);
        if (trimmed.size() < messages.size()) {
            log.info("对话历史 {} 条，超出上限 {}，已丢弃最早 {} 条",
                    messages.size(), MAX_HISTORY_MESSAGES, messages.size() - trimmed.size());
        }

        // ⚠️ 传下去的 trimmed 是**截断后**的历史，而 persona 的依据要在这份历史里逐字回查
        //    （见 ChatComposer 里 screen 那一段）。这不是疏忽：我们只认模型**看得见**的东西 ——
        //    它没看到某句话却拿那句话当依据，本来就更可疑。
        //    代价是聊得特别久之后，早期原话会被挤出窗口、依据查不中而丢那一轮的新推论；
        //    已经合并进 currentPersona 的旧条目不受影响（增量合并只覆盖本轮出现的 key）。
        return composer.chat(trimmed, request.currentOrEmpty(), request.currentPersonaOrEmpty());
    }

    /** 从最早的开始丢，并逐条限长 */
    private static List<Message> trim(List<Message> messages) {
        List<Message> tail = messages.size() <= MAX_HISTORY_MESSAGES
                ? messages
                : messages.subList(messages.size() - MAX_HISTORY_MESSAGES, messages.size());

        List<Message> out = new ArrayList<>(tail.size());
        for (Message m : tail) {
            String c = m.content();
            out.add(c.length() <= MAX_CONTENT_CHARS
                    ? m
                    : new Message(m.role(), c.substring(0, MAX_CONTENT_CHARS) + "…（已截断）"));
        }
        return out;
    }
}
