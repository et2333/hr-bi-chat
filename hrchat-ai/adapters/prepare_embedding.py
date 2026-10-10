"""Explicit public model download; normal ask requests never download or execute remote code."""
import json
from adapters.query_retrieval import MODEL_DIR, MODEL_ID, LocalEncoder


def main():
    from huggingface_hub import HfApi, snapshot_download
    info = HfApi().model_info(MODEL_ID)
    snapshot_download(MODEL_ID, revision=info.sha, local_dir=MODEL_DIR,
        allow_patterns=["*.json", "*.txt", "model.safetensors", "1_Pooling/*"])
    vectors = LocalEncoder().encode(["在职人数", "入职人数", "离职人数"])
    manifest = {"model": MODEL_ID, "revision": info.sha, "dimension": len(vectors[0]),
                "device": "cpu", "source": "https://huggingface.co/" + MODEL_ID}
    (MODEL_DIR / "hrchat-model.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest))


if __name__ == "__main__":
    main()
