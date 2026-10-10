"""Real-model demo entrypoint. Configuration validation never sends a model request."""
import os


def main():
    from adapters.local_env import load_local_model_env
    load_local_model_env(override=True)
    if not os.getenv("OPENAI_API_KEY", "").strip():
        raise SystemExit("Configure OPENAI_API_KEY in hrchat-ai/.env.local before starting the AI demo.")
    if not os.getenv("OPENAI_MODEL", "").strip() or not os.getenv("OPENAI_BASE_URL", "").strip():
        raise SystemExit("Configure OPENAI_MODEL and OPENAI_BASE_URL in .env.local.")
    # This entrypoint deliberately overrides stale mock/demo shell settings.
    os.environ["LLM_PROFILE"] = "openai"
    os.environ["QUERY_BACKEND"] = "java_mcp"
    os.environ.setdefault("HRCHAT_RAG_MODE", "hybrid")
    if os.environ["HRCHAT_RAG_MODE"] == "hybrid":
        from adapters.query_retrieval import encoder
        encoder()  # Fail early with an actionable setup error; no online model request.
    os.environ.setdefault("JAVA_MCP_BASE_URL", "http://127.0.0.1:8080/mcp")
    os.environ.setdefault("HRCHAT_MCP_SERVICE_TOKEN", "local-dev-mcp-service-token")
    import uvicorn
    uvicorn.run("agent_gateway.app:app", host="127.0.0.1", port=8000)


if __name__ == "__main__":
    main()
