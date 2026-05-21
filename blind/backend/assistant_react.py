from __future__ import annotations

import json
import socket
from typing import Any, Callable
from urllib.request import Request, urlopen


class AssistantReActEngine:
    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str,
        timeout_sec: float = 20.0,
        max_iterations: int = 10,
    ) -> None:
        self.api_key = api_key.strip()
        self.model = model.strip() or "qwen-plus"
        self.base_url = base_url.strip()
        self.timeout_sec = max(5.0, timeout_sec)
        self.max_iterations = max(1, max_iterations)

    @property
    def enabled(self) -> bool:
        return bool(self.api_key and self.base_url)

    @staticmethod
    def _is_timeout_error(err: Exception) -> bool:
        if isinstance(err, (socket.timeout, TimeoutError)):
            return True
        msg = str(err).lower()
        return "timed out" in msg or "timeout" in msg

    @staticmethod
    def _extract_json_text(raw: str) -> str:
        text = raw.strip()
        if text.startswith("```"):
            first = text.find("{")
            last = text.rfind("}")
            if first >= 0 and last > first:
                return text[first : last + 1]
        return text

    def _call_llm_once(self, messages: list[dict[str, str]]) -> dict[str, Any]:
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }
        req = Request(
            self.base_url,
            method="POST",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
        )
        with urlopen(req, timeout=self.timeout_sec) as resp:
            body = resp.read().decode("utf-8", errors="ignore")
        obj = json.loads(body)
        content = (
            (((obj.get("choices") or [{}])[0]).get("message") or {}).get("content", "")
            if isinstance(obj, dict)
            else ""
        )
        if isinstance(content, list):
            content = "".join([p.get("text", "") if isinstance(p, dict) else str(p) for p in content])
        parsed = json.loads(self._extract_json_text(str(content)))
        if not isinstance(parsed, dict):
            raise ValueError("LLM did not return a JSON object")
        return parsed

    def _call_llm(self, messages: list[dict[str, str]]) -> dict[str, Any]:
        # Permanent stabilization: retry once for timeout-like failures.
        last_err: Exception | None = None
        for idx in range(2):
            try:
                return self._call_llm_once(messages)
            except Exception as e:
                last_err = e
                if idx == 0 and self._is_timeout_error(e):
                    continue
                raise
        if last_err is not None:
            raise last_err
        raise RuntimeError("LLM call failed with unknown error")

    def run(
        self,
        message: str,
        user_location: dict[str, float] | None,
        chat_history: list[dict[str, str]],
        execute_tool: Callable[[str, dict[str, Any]], dict[str, Any]],
    ) -> dict[str, Any]:
        if not self.enabled:
            return {
                "status": "error",
                "content": "AI 助手未启用：请配置 DASHSCOPE_API_KEY。",
                "iterations": 0,
                "tool_history": [],
                "error": "assistant_disabled",
            }

        system_prompt = (
            "你是面向视障用户的出行助手。必须只输出 JSON。\n"
            "允许两种输出结构：\n"
            '1) {"type":"tool_call","action":"...","params":{...},"reasoning":"..."}\n'
            '2) {"type":"answer","content":"...","reasoning":"..."}\n'
            "可用 action:\n"
            "- geocoding: {address, city?}\n"
            "- reverse_geocoding: {lat, lng, coordtype?}\n"
            "- search_nearby_places: {query, lat, lng, radius?}\n"
            "- calculate_route: {origin_lat, origin_lng, dest_lat, dest_lng, mode?, coordtype?}\n"
            "若信息不足优先调用工具；最后必须输出简洁、可执行的中文回答。"
        )

        tool_history: list[dict[str, Any]] = []
        iterations = 0
        final_content = ""
        error_text = ""

        for i in range(self.max_iterations):
            iterations = i + 1
            user_payload = {
                "message": message,
                "user_location": user_location,
                "chat_history": chat_history[-8:],
                "tool_history": tool_history,
            }
            messages = [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": json.dumps(user_payload, ensure_ascii=False)},
            ]
            try:
                decision = self._call_llm(messages)
            except Exception as e:
                error_text = f"llm_call_failed: {e}"
                break

            decision_type = str(decision.get("type", "")).strip().lower()
            reasoning = str(decision.get("reasoning", "")).strip()
            if decision_type == "answer":
                final_content = str(decision.get("content", "")).strip()
                if not final_content:
                    final_content = "我暂时无法给出可靠结论，请稍后重试。"
                return {
                    "status": "ok",
                    "content": final_content,
                    "iterations": iterations,
                    "tool_history": tool_history,
                    "error": "",
                }

            if decision_type != "tool_call":
                error_text = "invalid_llm_response_type"
                break

            action = str(decision.get("action", "")).strip()
            params = decision.get("params", {})
            if not isinstance(params, dict):
                params = {}
            try:
                result = execute_tool(action, params)
            except Exception as e:
                result = {"success": False, "error": f"tool_exec_failed: {e}"}
            tool_history.append(
                {
                    "step": len(tool_history) + 1,
                    "action": action or "unknown",
                    "params": params,
                    "reasoning": reasoning,
                    "result": result if isinstance(result, dict) else {"raw": str(result)},
                }
            )

        if not final_content:
            if tool_history:
                final_content = "我已完成工具检索，但当前无法稳定生成最终回答，请重试一次。"
            else:
                final_content = "AI 助手暂时不可用，请检查网络或模型配置后重试。"
        return {
            "status": "error" if error_text else "ok",
            "content": final_content,
            "iterations": iterations,
            "tool_history": tool_history,
            "error": error_text,
        }
