# Diagnostic Log Points for pocket_llama.cpp

These 5 log points instrument the full inference pipeline. Add them ALL in a single
build so you capture everything in one test run.

All log points use the `PocketLlamaJNI` tag and `ANDROID_LOG_INFO` level, making them
filterable with `adb logcat -s PocketLlamaJNI:I`.

## Log Point 1: Vocab Metadata + Special Token Verification

**Purpose**: Verify the model's vocabulary is loaded correctly and special tokens
(ChatML markers) tokenize as single tokens, not fragmented text.

**Insert after**: The `llama_model_get_vocab()` call in `nativeGenerate()`, around line 2265.

**What it reveals**:
- `n_vocab`: Total vocabulary size (Qwen3: 151936, Qwen3.5: 248320, Phi-4: ~100352)
- `add_bos`/`add_eos`: Whether BOS/EOS are auto-prepended (should be false for Qwen)
- `spec_enabled`: Whether speculative decoding is active
- Special token tokenization: Each should produce `n_tokens=1`

```cpp
// === DIAGNOSTIC LOG POINT 1: Vocab metadata + special token verification ===
{
    const llama_token bos = llama_vocab_bos(vocab);
    const llama_token eos = llama_vocab_eos(vocab);
    const bool add_bos = llama_vocab_get_add_bos(vocab);
    const bool add_eos = llama_vocab_get_add_eos(vocab);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "DIAG_VOCAB|n_vocab=%d|bos=%d|eos=%d|add_bos=%s|add_eos=%s|spec_enabled=%s",
        n_vocab, bos, eos,
        add_bos ? "true" : "false",
        add_eos ? "true" : "false",
        g_speculative_enabled ? "true" : "false");
    const char * test_strs[] = {"<|im_start|>", "<|im_end|>", "<|endoftext|>"};
    for (const char * ts : test_strs) {
        std::vector<llama_token> tmp(16);
        int n = llama_tokenize(vocab, ts, static_cast<int32_t>(strlen(ts)),
                               tmp.data(), 16, false, true);
        std::string ids;
        for (int j = 0; j < std::abs(n) && j < 8; ++j) {
            if (!ids.empty()) ids += ",";
            ids += std::to_string(tmp[j]);
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "DIAG_SPECIAL|text=%s|n_tokens=%d|ids=[%s]", ts, n, ids.c_str());
    }
}
```

**Interpretation**:
- `n_tokens=1` for each special token: GOOD -- tokenizer recognizes them
- `n_tokens>1`: BAD -- special tokens are being fragmented into subwords
- `add_bos=true` for Qwen: BAD -- Qwen sets `add_bos_token=false` in its config

## Log Point 2: Prompt Preview + Token IDs + Context Budget

**Purpose**: See the actual prompt string, its tokenized form, and critically whether
the context budget leaves enough room for the prompt.

**Insert after**: The `tokenize_prompt()` call, around line 2288.

**What it reveals**:
- `bytes`: Raw prompt size in bytes
- `preview`: First 500 chars of the prompt (verify template rendering)
- `total`: Number of tokens after tokenization
- `max_prompt`: Maximum tokens allowed for prompt = n_ctx - safe_max_tokens - 1
- `ctx`: Runtime context window size

```cpp
// === DIAGNOSTIC LOG POINT 2: Prompt preview + token IDs ===
{
    std::string preview = prompt_text.substr(0, 500);
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "DIAG_PROMPT|bytes=%zu|preview=%s",
        prompt_text.size(), preview.c_str());
    std::string ids;
    for (size_t i = 0; i < std::min(prompt_tokens.size(), static_cast<size_t>(40)); ++i) {
        if (!ids.empty()) ids += ",";
        ids += std::to_string(prompt_tokens[i]);
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "DIAG_TOKENS|total=%zu|max_prompt=%d|ctx=%d|first40=[%s]",
        prompt_tokens.size(), max_prompt_tokens, runtime_context_size,
        ids.c_str());
    std::string decoded;
    for (size_t i = 0; i < std::min(prompt_tokens.size(), static_cast<size_t>(10)); ++i) {
        std::string piece;
        token_to_piece_dynamic(vocab, prompt_tokens[i], &piece);
        decoded += "[" + std::to_string(prompt_tokens[i]) + "=" + piece + "]";
    }
    __android_log_print(ANDROID_LOG_INFO, TAG,
        "DIAG_TOKEN_DECODE|first10=%s", decoded.c_str());
}
```

**Interpretation**:
- `max_prompt >= total`: GOOD -- entire prompt fits in context
- `max_prompt < total`: BAD -- prompt will be trimmed, losing content
- `max_prompt <= 1`: CRITICAL -- almost no prompt reaches the model (root cause of garbled output)
- First token should be `<|im_start|>` (ChatML template start)
- Check `preview` to verify system prompt and tool definitions are present

**The critical formula**: `max_prompt_tokens = max(1, n_ctx - safe_max_tokens - 1)`

If `maxTokens` (generation budget) is close to or exceeds `n_ctx`, the prompt gets
almost no space. This was the root cause of the "garbled output" bug: `maxTokens=2048`
with `n_ctx=1536` left only 1 token for the entire prompt.

## Log Point 3: Prefix Cache State

**Purpose**: Check whether the prefix cache is being used and whether it's serving
stale state from a previous conversation.

**Insert after**: The prefix cache lookup, around line 2357.

```cpp
// === DIAGNOSTIC LOG POINT 3: Prefix cache state ===
__android_log_print(ANDROID_LOG_INFO, TAG,
    "DIAG_CACHE|policy=%d|slot=%d|reused=%zu|hit=%s|strict=%s|decode_offset=%zu",
    cache_policy, cache_slot, reused_tokens,
    cache_hit ? "true" : "false",
    strict_key_match ? "true" : "false",
    decode_offset);
```

**Interpretation**:
- `hit=false` on first message: GOOD -- no stale cache
- `hit=true` on first message of new conversation: SUSPICIOUS -- may be reusing old state
- `reused=0`: Cache didn't help (expected for first message)
- `reused>0` with `strict=false`: Soft match, could be serving slightly wrong context

## Log Point 4: Sampled Token IDs + Decoded Text

**Purpose**: See the actual tokens the model generates. This is the ground truth for
whether the model is producing coherent output.

**Insert in**: The token emission loop, around line 2569.

```cpp
// === DIAGNOSTIC LOG POINT 4: First 10 sampled tokens ===
{
    const int global_index = emitted_token_count + static_cast<int>(ei);
    if (global_index < 10) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "DIAG_SAMPLE|index=%d|token_id=%d|piece=%s|spec_active=%s",
            global_index, token, piece.c_str(),
            speculative_ready ? "true" : "false");
    }
}
```

**Interpretation**:
- Coherent English words: GOOD -- model understands the prompt
- `<think>` followed by reasoning: GOOD -- model is in thinking mode (Qwen3 behavior)
- Random numbers, symbols, fragments: BAD -- model has no context (prompt trimmed?)
- All same token repeated: BAD -- sampling issue or degenerate loop

## Log Point 5: Sampling Parameters

**Purpose**: Verify the actual sampling parameters reaching the native layer match
what was configured in the UI/profiles.

**Insert in**: `nativeSetSamplingConfig()`, around line 3143.

```cpp
// === DIAGNOSTIC LOG POINT 5: Sampling parameters ===
__android_log_print(ANDROID_LOG_INFO, TAG,
    "DIAG_SAMPLING|temp=%.3f|top_k=%d|top_p=%.3f|min_p=%.3f|rep_pen=%.3f|seed=%d|n_keep=%d",
    static_cast<float>(temperature), static_cast<int>(topK),
    static_cast<float>(topP), static_cast<float>(minP),
    static_cast<float>(repeatPenalty), static_cast<int>(seed),
    static_cast<int>(nKeep));
```

**Interpretation**:
- `temp=0.000`: Greedy decoding -- deterministic but may be too rigid
- `temp=0.600, top_k=40, top_p=0.950`: Normal BALANCED profile defaults
- `temp>1.0`: Very random -- could explain garbled output from small models
- `n_keep=128`: Number of initial tokens protected from cache eviction
- Check that values match `PerformanceProfiles.kt` expectations

## Removal

After the bug is fixed and verified, remove all 5 log points (or gate them behind
`#ifdef POCKET_LLAMA_DIAG`). They add overhead to the hot path -- especially Log Point 4
which runs for every emitted token.
