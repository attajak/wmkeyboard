package com.wasimaster.wmkeyboard.core.localllm

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.intelligence.R

/**
 * Container format of a model file. Both load through the LiteRT-LM engine;
 * `.task` is the older MediaPipe bundle kept for models that don't publish
 * a `.litertlm` yet.
 */
enum class ModelFormat(val extension: String) {
    LITERTLM("litertlm"), TASK("task"),
}

/**
 * How much a model can be trusted, shown as a badge next to its name.
 * [STANDARD] is the unremarkable middle — it gets no badge, so its
 * [badgeRes] is null.
 */
enum class ModelTier(@StringRes val badgeRes: Int?) {
    /** Verified good on this keyboard's prompts. */
    RECOMMENDED(R.string.core_intel_llm_tier_recommended_label),
    STANDARD(null),
    /** In the catalog but never actually exercised here. */
    UNTESTED(R.string.core_intel_llm_tier_untested_label),
    /** Works, but small enough that output quality is a gamble. */
    EXPERIMENTAL(R.string.core_intel_llm_tier_experimental_label),
}

/** One downloadable model in the curated catalog. */
data class LocalLlmModel(
    /** Stable key — used as the storage directory name and the settings value. */
    val id: String,
    val displayName: String,
    /** Parameter count as shown to the user, e.g. "270M". */
    val params: String,
    /** Hugging Face repo, e.g. "litert-community/Gemma3-1B-IT". */
    val repo: String,
    /** Exact file under the repo's resolve/main/. */
    val fileName: String,
    /** Approximate size — preflight space check and progress fallback. */
    val sizeBytes: Long,
    /** Gated repos need an accepted license + HF token to download. */
    val gated: Boolean,
    /**
     * The licence the weights are published under, as the repository's own
     * model card names it: an SPDX identifier where there is one, or the
     * name of the vendor's terms. A licence identifier, so never translated.
     * Shown on the model row so the terms are in view before the download,
     * gated or not. Checked against huggingface.co/api/models on 2026-09-22.
     */
    val license: String,
    val format: ModelFormat,
    val tier: ModelTier,
    /** Advisory total-device-RAM floor; the UI warns below this, never blocks. */
    val minRamMb: Int,
    /** One-line description for the model row; resolved by the UI layer. */
    @StringRes val descriptionRes: Int,
    /**
     * Reasoning model whose template opens a think block in the prompt —
     * output is bare reasoning up to a `</think>` (see AiThinking).
     */
    val reasoning: Boolean = false,
)

/**
 * Curated on-device models, best first. All files are 4/8-bit mobile
 * quantizations published by Google's litert-community on Hugging Face.
 *
 * The order is a quality ranking, not a size ranking — the settings list and
 * the panel's model picker both render in catalog order, and what a user
 * wants at the top is the model most likely to write well, not the one that
 * downloads fastest. Adding a model = inserting one entry at its rank.
 */
object LocalLlmCatalog {

    const val TOKEN_URL = "https://huggingface.co/settings/tokens"

    val models: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            id = "gemma4-e2b",
            displayName = "Gemma 4 E2B",
            params = "2B",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            sizeBytes = 2_590_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.RECOMMENDED,
            minRamMb = 6144,
            descriptionRes = R.string.core_intel_llm_model_gemma4_e2b_subtitle,
        ),
        LocalLlmModel(
            id = "gemma4-e4b",
            displayName = "Gemma 4 E4B",
            params = "4B",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            sizeBytes = 3_660_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.RECOMMENDED,
            minRamMb = 8192,
            descriptionRes = R.string.core_intel_llm_model_gemma4_e4b_subtitle,
        ),
        LocalLlmModel(
            id = "gemma3-1b",
            displayName = "Gemma 3 1B",
            params = "1B",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_000_000L,
            gated = true,
            license = "Gemma Terms of Use",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.UNTESTED,
            minRamMb = 4096,
            descriptionRes = R.string.core_intel_llm_model_gemma3_1b_subtitle,
        ),
        LocalLlmModel(
            id = "qwen25-1.5b",
            displayName = "Qwen 2.5 1.5B",
            params = "1.5B",
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeBytes = 1_600_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.STANDARD,
            minRamMb = 6144,
            descriptionRes = R.string.core_intel_llm_model_qwen25_15b_subtitle,
        ),
        LocalLlmModel(
            id = "qwen3-0.6b",
            displayName = "Qwen 3 0.6B",
            params = "0.6B",
            repo = "litert-community/Qwen3-0.6B-int4",
            fileName = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            sizeBytes = 347_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen3_06b_subtitle,
            reasoning = true,
        ),
        LocalLlmModel(
            id = "qwen25-0.5b",
            displayName = "Qwen 2.5 0.5B",
            params = "0.5B",
            repo = "litert-community/Qwen2.5-0.5B-Instruct",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeBytes = 546_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.TASK,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 3072,
            descriptionRes = R.string.core_intel_llm_model_qwen25_05b_subtitle,
        ),
        LocalLlmModel(
            id = "gemma3-270m",
            displayName = "Gemma 3 270M",
            params = "270M",
            repo = "litert-community/gemma-3-270m-it",
            fileName = "gemma3-270m-it-q8.litertlm",
            sizeBytes = 304_000_000L,
            gated = true,
            license = "Gemma Terms of Use",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.UNTESTED,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_gemma3_270m_subtitle,
        ),
        LocalLlmModel(
            // SmolLM v1's .task bundle behaved like a base model (no chat
            // template applied); the v2 .litertlm is the proper instruct build.
            id = "smollm2-135m",
            displayName = "SmolLM2 135M",
            params = "135M",
            repo = "litert-community/SmolLM2-135M-Instruct",
            fileName = "SmolLM2_135M_Instruct.litertlm",
            sizeBytes = 143_000_000L,
            gated = false,
            license = "Apache-2.0",
            format = ModelFormat.LITERTLM,
            tier = ModelTier.EXPERIMENTAL,
            minRamMb = 2048,
            descriptionRes = R.string.core_intel_llm_model_smollm2_135m_subtitle,
        ),
    )

    init {
        check(models.map { it.id }.toSet().size == models.size) { "catalog ids must be unique" }
    }

    fun byId(id: String): LocalLlmModel? = models.firstOrNull { it.id == id }

    fun downloadUrl(model: LocalLlmModel): String =
        "https://huggingface.co/${model.repo}/resolve/main/${model.fileName}"

    /** The repo page: the licence text, and where a gated model's terms are accepted. */
    fun licenseUrl(model: LocalLlmModel): String = "https://huggingface.co/${model.repo}"
}
