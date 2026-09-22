package moe.reimu.nekoassistant.ai

data class ActionResult(val stop: Boolean, val prompt: String) {
    companion object {
        fun success(prompt: String = "操作完成，请验证操作是否正确，然后进行下一步") = ActionResult(false, prompt)
        fun fail(prompt: String = "操作失败") = ActionResult(true, prompt)
        fun retry(prompt: String) = ActionResult(false, prompt)
    }
}
