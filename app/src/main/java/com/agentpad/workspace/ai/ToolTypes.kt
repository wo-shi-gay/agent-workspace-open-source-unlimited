package com.agentpad.workspace.ai

data class ToolCall(val id: String, val name: String, val argumentsJson: String)
data class ToolResult(val id: String, val name: String, val content: String)

data class ToolSpec(val name: String, val description: String, val parametersSchema: String)
