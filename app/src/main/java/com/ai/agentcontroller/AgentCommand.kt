package com.ai.agentcontroller

/**
 * AI 返回的操作指令模型。
 *
 * AI 在回复中输出如下 JSON（可放在 ```json 代码块中），应用会解析并逐步执行：
 *
 * {
 *   "explain": "打开微信并搜索张三",
 *   "commands": [
 *     {"action":"open_app","target":"微信"},
 *     {"action":"click","target":"搜索"},
 *     {"action":"input_text","target":"搜索","text":"张三"},
 *     {"action":"click","target":"张三"},
 *     {"action":"back"},
 *     {"action":"scroll","direction":"down","amount":500},
 *     {"action":"wait","ms":800},
 *     {"action":"tap","x":540,"y":1200},
 *     {"action":"home"}
 *   ]
 * }
 */
data class AgentCommand(
    val action: String,
    val target: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val amount: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val ms: Long = 300L,
    val pkg: String? = null
)

data class CommandBatch(
    val explain: String = "",
    val commands: List<AgentCommand> = emptyList()
)
