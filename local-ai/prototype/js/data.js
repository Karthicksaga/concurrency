/* SAMPLE DATA ONLY. Model specs are approximations of public GGUF releases for illustration; device figures are invented. */
window.LA = window.LA || {};
(function () {
  const now = Date.now();
  const ago = (min) => now - min * 60000;

  const devices = {
    '4':  { id: '4',  label: '4 GB phone', ramGB: 4,  availGB: 1.4, soc: 'Helio G85 (sample)', gpu: 'Mali-G52', cpu: '2× 2.0 + 6× 1.8 GHz', perfCores: 2, freeStorageGB: 21.6, android: 'Android 12', features: 'NEON', genTps: 6.2, promptTps: 28, lite: true },
    '6':  { id: '6',  label: '6 GB phone', ramGB: 6,  availGB: 2.3, soc: 'Snapdragon 695 (sample)', gpu: 'Adreno 619', cpu: '2× 2.2 + 6× 1.8 GHz', perfCores: 2, freeStorageGB: 44.0, android: 'Android 13', features: 'NEON · dotprod', genTps: 8.4, promptTps: 41, lite: true },
    '8':  { id: '8',  label: '8 GB phone', ramGB: 8,  availGB: 3.4, soc: 'Snapdragon 8 Gen 2', gpu: 'Adreno 740', cpu: '1× 3.2 + 4× 2.8 + 3× 2.0 GHz', perfCores: 5, freeStorageGB: 38.4, android: 'Android 15', features: 'NEON · dotprod · i8mm', genTps: 11.8, promptTps: 62 },
    '12': { id: '12', label: '12 GB phone', ramGB: 12, availGB: 5.8, soc: 'Snapdragon 8 Gen 3', gpu: 'Adreno 750', cpu: '1× 3.3 + 5× 3.2 + 2× 2.3 GHz', perfCores: 6, freeStorageGB: 112.7, android: 'Android 16', features: 'NEON · dotprod · i8mm', genTps: 14.6, promptTps: 88 },
    '16': { id: '16', label: '16 GB phone', ramGB: 16, availGB: 8.6, soc: 'Snapdragon 8 Elite', gpu: 'Adreno 830', cpu: '2× 4.3 + 6× 3.5 GHz', perfCores: 8, freeStorageGB: 201.3, android: 'Android 16', features: 'NEON · dotprod · i8mm · SME', genTps: 19.2, promptTps: 131 }
  };

  // layers, kvHeads, headDim drive the KV-cache estimate (see MODEL_RUNTIME_DESIGN.md §5)
  const models = [
    { id: 'qwen3-0-6b', name: 'Qwen3 0.6B', family: 'Qwen3', params: 0.6, quant: 'Q8_0', sizeGB: 0.60, arch: 'qwen3', ctxTrain: 40960, layers: 28, kvHeads: 8, headDim: 128, license: 'Apache-2.0', purpose: 'gen', state: 'available', tags: ['Lite', 'Multilingual'], desc: 'Smallest model in the catalog. Fast on any phone; best for short questions.' },
    { id: 'llama-3-2-1b', name: 'Llama 3.2 1B Instruct', family: 'Llama', params: 1.24, quant: 'Q4_K_M', sizeGB: 0.75, arch: 'llama', ctxTrain: 131072, layers: 16, kvHeads: 8, headDim: 64, license: 'Llama 3.2 Community License', purpose: 'gen', state: 'available', tags: ['Lite', 'General'], desc: 'Recommended for 4 GB phones (Lite mode).' },
    { id: 'gemma-3-1b', name: 'Gemma 3 1B IT', family: 'Gemma', params: 1.0, quant: 'Q4_K_M', sizeGB: 0.75, arch: 'gemma3', ctxTrain: 32768, layers: 26, kvHeads: 1, headDim: 256, license: 'Gemma Terms of Use', purpose: 'gen', state: 'available', tags: ['Lite', 'Multilingual'], desc: 'Compact Gemma model with a tiny context cache.' },
    { id: 'qwen3-4b', name: 'Qwen3 4B Instruct', family: 'Qwen3', params: 4.0, quant: 'Q4_K_M', sizeGB: 2.50, arch: 'qwen3', ctxTrain: 32768, layers: 36, kvHeads: 8, headDim: 128, license: 'Apache-2.0', purpose: 'gen', state: 'installed', tags: ['General', 'Multilingual', 'Tool use'], desc: 'Strong all-rounder for its size with reliable JSON output. Recommended default on 8–12 GB devices.' },
    { id: 'llama-3-2-3b', name: 'Llama 3.2 3B Instruct', family: 'Llama', params: 3.2, quant: 'Q4_K_M', sizeGB: 2.02, arch: 'llama', ctxTrain: 131072, layers: 28, kvHeads: 8, headDim: 128, license: 'Llama 3.2 Community License', purpose: 'gen', state: 'installed', tags: ['General', 'Long context'], desc: 'Fast, compact chat model with long context support. Good fit for 8 GB devices.' },
    { id: 'phi-4-mini', name: 'Phi-4-mini Instruct', family: 'Phi', params: 3.8, quant: 'Q4_K_M', sizeGB: 2.49, arch: 'phi3', ctxTrain: 131072, layers: 32, kvHeads: 8, headDim: 128, license: 'MIT', purpose: 'gen', state: 'downloading', tags: ['Reasoning', 'Math'], desc: 'Reasoning-focused small model. Strong on math and structured tasks.' },
    { id: 'gemma-3-4b', name: 'Gemma 3 4B IT', family: 'Gemma', params: 4.3, quant: 'Q4_0', sizeGB: 2.37, arch: 'gemma3', ctxTrain: 131072, layers: 34, kvHeads: 4, headDim: 256, license: 'Gemma Terms of Use', purpose: 'gen', state: 'downloading', tags: ['General', 'Multilingual', 'Vision'], vision: true, desc: 'Q4_0 quantisation benefits from ARM weight repacking. Can also look at photos (image encoder included).' },
    { id: 'smollm2-1-7b', name: 'SmolLM2 1.7B Instruct', family: 'SmolLM', params: 1.7, quant: 'Q4_K_M', sizeGB: 1.06, arch: 'llama', ctxTrain: 8192, layers: 24, kvHeads: 32, headDim: 64, license: 'Apache-2.0', purpose: 'gen', state: 'available', tags: ['Lite', 'English'], desc: 'Fully open small model. Runs on 6 GB phones in Lite mode.' },
    { id: 'qwen3-1-7b', name: 'Qwen3 1.7B', family: 'Qwen3', params: 1.7, quant: 'Q4_K_M', sizeGB: 1.03, arch: 'qwen3', ctxTrain: 32768, layers: 28, kvHeads: 8, headDim: 128, license: 'Apache-2.0', purpose: 'gen', state: 'available', tags: ['Lite', 'Multilingual'], desc: 'Recommended for 6 GB phones (Lite mode). Good router/supervisor candidate.' },
    { id: 'qwen2-5-coder-7b', name: 'Qwen2.5 Coder 7B Instruct', family: 'Qwen2.5', params: 7.6, quant: 'Q4_K_M', sizeGB: 4.68, arch: 'qwen2', ctxTrain: 32768, layers: 28, kvHeads: 4, headDim: 128, license: 'Apache-2.0', purpose: 'gen', state: 'available', tags: ['Code'], desc: 'Code-specialised model. Needs a 12 GB device for comfortable use.' },
    { id: 'llama-3-1-8b', name: 'Llama 3.1 8B Instruct', family: 'Llama', params: 8.0, quant: 'Q4_K_M', sizeGB: 4.92, arch: 'llama', ctxTrain: 131072, layers: 32, kvHeads: 8, headDim: 128, license: 'Llama 3.1 Community License', purpose: 'gen', state: 'available', tags: ['General', 'Tool use'], desc: 'Capable 8B generalist. Best on 12–16 GB devices.' },
    { id: 'deepseek-r1-qwen-7b', name: 'DeepSeek R1 Distill Qwen 7B', family: 'DeepSeek', params: 7.6, quant: 'Q4_K_M', sizeGB: 4.68, arch: 'qwen2', ctxTrain: 131072, layers: 28, kvHeads: 4, headDim: 128, license: 'MIT', purpose: 'gen', state: 'available', tags: ['Reasoning'], desc: 'Produces long reasoning traces; slower answers, better on hard problems.' },
    { id: 'mistral-7b', name: 'Mistral 7B Instruct v0.3', family: 'Mistral', params: 7.2, quant: 'Q4_K_M', sizeGB: 4.37, arch: 'llama', ctxTrain: 32768, layers: 32, kvHeads: 8, headDim: 128, license: 'Apache-2.0', purpose: 'gen', state: 'available', tags: ['General'], desc: 'Well-known 7B baseline with native function calling format.' },
    { id: 'gemma-3-12b', name: 'Gemma 3 12B IT', family: 'Gemma', params: 12.0, quant: 'Q3_K_M', sizeGB: 6.01, arch: 'gemma3', ctxTrain: 131072, layers: 48, kvHeads: 8, headDim: 256, license: 'Gemma Terms of Use', purpose: 'gen', state: 'available', tags: ['General', 'Large'], desc: 'Largest model in the catalog. Only for 16 GB devices, with short context.' },
    { id: 'smolvlm-500m', name: 'SmolVLM 500M (vision)', family: 'SmolVLM', params: 0.5, quant: 'Q8_0', sizeGB: 0.55, arch: 'llama', ctxTrain: 8192, layers: 32, kvHeads: 5, headDim: 64, license: 'Apache-2.0', purpose: 'gen', state: 'installed', vision: true, tags: ['Vision', 'Lite'], desc: 'Small vision model: describes photos and reads short text and labels. Fits 4–6 GB phones. Model + image encoder, both checksum-verified.' },
    { id: 'smolvlm2-2-2b', name: 'SmolVLM2 2.2B (vision)', family: 'SmolVLM', params: 2.2, quant: 'Q4_K_M', sizeGB: 1.70, arch: 'llama', ctxTrain: 8192, layers: 24, kvHeads: 32, headDim: 64, license: 'Apache-2.0', purpose: 'gen', state: 'available', vision: true, tags: ['Vision'], desc: 'Better image understanding and text reading. Best on 8 GB phones.' },
    { id: 'qwen2-5-vl-3b', name: 'Qwen2.5-VL 3B (vision)', family: 'Qwen2.5', params: 3.75, quant: 'Q4_K_M', sizeGB: 2.77, arch: 'qwen2vl', ctxTrain: 32768, layers: 36, kvHeads: 2, headDim: 128, license: 'Qwen Research License', purpose: 'gen', state: 'available', vision: true, tags: ['Vision', 'Multilingual'], desc: 'Strong at reading documents, receipts and screenshots in photos. Needs a 12 GB phone.' },
    { id: 'me5-small', name: 'multilingual-e5-small', family: 'E5', params: 0.12, quant: 'Q8_0', sizeGB: 0.12, arch: 'bert (XLM-R)', ctxTrain: 512, layers: 12, kvHeads: 12, headDim: 32, license: 'MIT', purpose: 'embed', state: 'installed', dim: 384, tags: ['Embedding', 'Multilingual'], desc: 'Default embedding model for knowledge bases and agent memory (384 dimensions).' },
    { id: 'bge-small-en', name: 'bge-small-en-v1.5', family: 'BGE', params: 0.03, quant: 'F16', sizeGB: 0.07, arch: 'bert', ctxTrain: 512, layers: 12, kvHeads: 12, headDim: 32, license: 'MIT', purpose: 'embed', state: 'available', dim: 384, tags: ['Embedding', 'English'], desc: 'Small English-only embedding model. Fastest indexing.' }
  ];

  const downloads = [
    { id: 'dl-phi', modelId: 'phi-4-mini', progress: 0.38, speedMBs: 14.2, state: 'running' },
    { id: 'dl-gemma', modelId: 'gemma-3-4b', progress: 0.71, speedMBs: 0, state: 'paused' }
  ];

  const tools = [
    { id: 'calculator', name: 'Calculator', icon: 'calc', risk: 'READ', permission: 'ALLOWED', desc: 'Evaluates arithmetic expressions with a safe parser. No variables, no code.', schema: { type: 'object', required: ['expression'], properties: { expression: { type: 'string', maxLength: 200, description: 'e.g. (1250 * 12) * 0.18' } } } },
    { id: 'file_search', name: 'File Search', icon: 'folder', risk: 'READ', permission: 'ASK', desc: 'Finds files by name and type inside folders you have granted. Returns names, not contents.', schema: { type: 'object', required: ['query'], properties: { query: { type: 'string', maxLength: 120 }, extensions: { type: 'array', items: { type: 'string' }, maxItems: 8 }, max_results: { type: 'integer', minimum: 1, maximum: 20 } } } },
    { id: 'document_reader', name: 'Document Reader', icon: 'file', risk: 'READ', permission: 'ALLOWED', desc: 'Reads pages of a document in the agent’s knowledge bases, up to 2,000 tokens per call.', schema: { type: 'object', required: ['document_id'], properties: { document_id: { type: 'string' }, page_range: { type: 'string', pattern: '^\\d+(-\\d+)?$' } } } },
    { id: 'note_manager', name: 'Note Manager', icon: 'note', risk: 'WRITE_LOCAL', permission: 'ASK', desc: 'Lists, reads, creates and updates your notes. Deleting always asks first.', schema: { type: 'object', required: ['action'], properties: { action: { enum: ['list', 'read', 'create', 'update', 'delete'] }, note_id: { type: 'string' }, title: { type: 'string', maxLength: 120 }, body: { type: 'string', maxLength: 4000 } } } },
    { id: 'knowledge_search', name: 'Knowledge Search', icon: 'knowledge', risk: 'READ', permission: 'ALLOWED', desc: 'Hybrid semantic + keyword search over the agent’s knowledge bases with source references.', schema: { type: 'object', required: ['query'], properties: { query: { type: 'string', maxLength: 300 }, kb_ids: { type: 'array', items: { type: 'string' } }, k: { type: 'integer', minimum: 1, maximum: 10 } } } },
    { id: 'sqlite_query', name: 'SQLite Query', icon: 'database', risk: 'READ', permission: 'ASK', desc: 'Runs read-only SELECT queries on your imported tables. Writes, PRAGMAs and attachments are blocked.', schema: { type: 'object', required: ['sql'], properties: { sql: { type: 'string', maxLength: 1000, description: 'SELECT only' }, max_rows: { type: 'integer', minimum: 1, maximum: 200 } } } },
    { id: 'code_search', name: 'Code Search', icon: 'code', risk: 'READ', permission: 'ALLOWED', desc: 'Searches indexed source code by meaning and by symbol name.', schema: { type: 'object', required: ['query', 'kb_id'], properties: { query: { type: 'string', maxLength: 200 }, kb_id: { type: 'string' }, language: { type: 'string' } } } },
    { id: 'delegate_to_agent', name: 'Agent Delegation', icon: 'delegate', risk: 'DELEGATE', permission: 'ALLOWED', desc: 'Hands a sub-task to another agent in the same workflow. Maximum depth 2.', schema: { type: 'object', required: ['agent_id', 'instruction'], properties: { agent_id: { type: 'string' }, instruction: { type: 'string', maxLength: 1000 } } } }
  ];

  const templates = [
    { id: 'research', name: 'Research Agent', role: 'Researcher', icon: 'search', color: 'model', desc: 'Finds and synthesises information from your knowledge bases with citations.', tools: ['knowledge_search', 'document_reader', 'note_manager'], memory: true, prompt: 'You are a careful research assistant. Answer only from the provided sources and cite every claim as [chunk:N]. If the sources do not contain the answer, say so and suggest what to import.' },
    { id: 'coding', name: 'Coding Agent', role: 'Engineer', icon: 'code', color: 'agent', desc: 'Explains, reviews and drafts code using your indexed repositories.', tools: ['code_search', 'file_search', 'calculator'], memory: true, prompt: 'You are a senior software engineer. Prefer small, idiomatic, well-tested code. Reference files you used. Never claim to have run code.' },
    { id: 'assistant', name: 'Personal Assistant', role: 'Assistant', icon: 'sparkle', color: 'accent', desc: 'Everyday help with notes, plans and quick calculations. Remembers your preferences.', tools: ['note_manager', 'calculator', 'knowledge_search'], memory: true, prompt: 'You are a concise, friendly personal assistant. Use the user’s saved preferences. Ask before creating or changing notes.' },
    { id: 'docanalyst', name: 'Document Analyst', role: 'Analyst', icon: 'file', color: 'knowledge', desc: 'Summarises, compares and extracts facts from long documents.', tools: ['document_reader', 'knowledge_search'], memory: false, prompt: 'You analyse documents precisely. Quote exact clauses with page numbers. Use tables for comparisons.' },
    { id: 'learning', name: 'Learning Assistant', role: 'Tutor', icon: 'star', color: 'warn', desc: 'Turns course material into explanations, quizzes and flashcards.', tools: ['knowledge_search', 'note_manager'], memory: true, prompt: 'You are a patient tutor. Explain step by step, check understanding with one question at a time, and adapt to the learner’s level.' },
    { id: 'data', name: 'Data Analyst', role: 'Data analyst', icon: 'database', color: 'ok', desc: 'Answers questions about your tables with read-only SQL and calculations.', tools: ['sqlite_query', 'calculator'], memory: false, prompt: 'You answer questions about the user’s tables. Write one read-only SELECT at a time, explain results, and never guess numbers.' }
  ];

  const kbs = [
    { id: 'kb-research', name: 'On-device ML papers', desc: 'Papers on quantisation, inference and small models.', scope: 'SHARED', color: 'model', embed: 'me5-small', chunkSize: 400, overlap: 60, updated: ago(12) },
    { id: 'kb-android', name: 'Android project docs', desc: 'Architecture notes and source of the companion app.', scope: 'SHARED', color: 'agent', embed: 'me5-small', chunkSize: 300, overlap: 0, updated: ago(60 * 26) },
    { id: 'kb-finance', name: 'Personal finance 2026', desc: 'Lease, salary slips, insurance and budget.', scope: 'AGENT', owner: 'ag-ledger', color: 'knowledge', embed: 'me5-small', chunkSize: 400, overlap: 60, updated: ago(60 * 24 * 3) },
    { id: 'kb-notes', name: 'Notes & trips', desc: 'Travel notes, meal plans and everyday lists.', scope: 'SHARED', color: 'warn', embed: 'me5-small', chunkSize: 400, overlap: 60, updated: ago(60 * 5) }
  ];

  const D = (id, kb, title, type, sizeMB, pages, chunks, status, extra) => Object.assign({ id, kb, title, type, sizeMB, pages, chunks, status, progress: status === 'INDEXED' ? 1 : 0, added: ago(60 * (3 + chunks % 40)) }, extra || {});
  const documents = [
    D('doc-attn', 'kb-research', 'Attention Is All You Need.pdf', 'PDF', 2.1, 15, 38, 'INDEXED', { excerpt: ['The dominant sequence transduction models are based on complex recurrent or convolutional neural networks that include an encoder and a decoder.', 'We propose a new simple network architecture, the Transformer, based solely on attention mechanisms, dispensing with recurrence and convolutions entirely.', 'Multi-head attention allows the model to jointly attend to information from different representation subspaces at different positions.'] }),
    D('doc-flash', 'kb-research', 'LLM in a flash — inference with limited memory.pdf', 'PDF', 3.4, 19, 47, 'INDEXED', { excerpt: ['Large language models are central to modern natural language processing, but their intensive computational and memory requirements present challenges for devices with limited DRAM capacity.', 'We store model parameters in flash memory and bring them on demand to DRAM, guided by a cost model that accounts for flash memory characteristics.', 'Windowing reduces data transfer by reusing previously activated neurons; row-column bundling increases the size of data chunks read from flash.'] }),
    D('doc-mobilellm', 'kb-research', 'MobileLLM — sub-billion parameter models.pdf', 'PDF', 1.8, 14, 33, 'INDEXED', { excerpt: ['We show that for sub-billion scale models, deep and thin architectures outperform wide and shallow ones.', 'Embedding sharing and grouped-query attention reduce parameter count with little quality loss.', 'Immediate block-wise weight sharing adds depth without increasing model size.'] }),
    D('doc-gptq', 'kb-research', 'GPTQ — post-training quantization.pdf', 'PDF', 1.2, 16, 41, 'INDEXED', { excerpt: ['GPTQ is a one-shot weight quantization method based on approximate second-order information.', 'It can quantize GPT models with 175 billion parameters to 3 or 4 bits per weight with negligible accuracy degradation.'] }),
    D('doc-qlora', 'kb-research', 'QLoRA — efficient finetuning.pdf', 'PDF', 1.5, 26, 64, 'INDEXED', { excerpt: ['QLoRA backpropagates gradients through a frozen, 4-bit quantized pretrained language model into Low Rank Adapters.', 'We introduce 4-bit NormalFloat, a data type that is information-theoretically optimal for normally distributed weights.'] }),
    D('doc-paged', 'kb-research', 'PagedAttention — memory management for LLM serving.pdf', 'PDF', 1.9, 16, 22, 'EMBEDDING', { progress: 0.62, excerpt: ['The key-value cache memory for each request is huge and grows and shrinks dynamically.', 'PagedAttention divides the KV cache of each sequence into blocks, each containing the keys and values for a fixed number of tokens.'] }),
    D('doc-smooth', 'kb-research', 'SmoothQuant.pdf', 'PDF', 1.1, 13, 0, 'QUEUED', { excerpt: ['SmoothQuant migrates quantization difficulty from activations to weights with a mathematically equivalent transformation.'] }),
    D('doc-readme', 'kb-android', 'README.md', 'Markdown', 0.02, null, 9, 'INDEXED', { excerpt: ['# Companion app\nThe app is organised into feature modules. Each feature exposes a single entry composable.', 'Build with ./gradlew assembleDebug. Native code lives under core/native and is built by CMake.'] }),
    D('doc-arch', 'kb-android', 'architecture.md', 'Markdown', 0.04, null, 14, 'INDEXED', { excerpt: ['ViewModels expose one StateFlow of UiState and accept intents. Side effects go through a Channel.', 'The inference scheduler is the only component allowed to call the engine.'] }),
    D('doc-chatvm', 'kb-android', 'ChatViewModel.kt', 'Kotlin', 0.01, null, 6, 'INDEXED', { excerpt: ['class ChatViewModel @Inject constructor(private val chat: ChatService) : ViewModel() {', 'private val tokens = MutableSharedFlow<String>(extraBufferCapacity = 256)'] }),
    D('doc-sched', 'kb-android', 'InferenceScheduler.kt', 'Kotlin', 0.01, null, 5, 'INDEXED', { excerpt: ['suspend fun <T> withSlot(priority: Priority, block: suspend () -> T): T = mutex.withLock { block() }'] }),
    D('doc-gradle', 'kb-android', 'build.gradle.kts', 'Kotlin script', 0.004, null, 2, 'INDEXED', { excerpt: ['android { ndkVersion = "28.0.13004108"; defaultConfig { ndk { abiFilters += "arm64-v8a" } } }'] }),
    D('doc-lease', 'kb-finance', 'Lease agreement — Flat 4B.pdf', 'PDF', 0.9, 11, 27, 'INDEXED', { excerpt: ['Clause 7.2 — Security deposit. The Tenant shall pay a refundable security deposit equal to two months’ rent, returned within 30 days of the end of the tenancy, less documented deductions.', 'Clause 9.1 — Notice. Either party may terminate this agreement by giving not less than sixty (60) days’ written notice.', 'Clause 11 — Maintenance. Minor repairs under 2,000 are the responsibility of the Tenant.'] }),
    D('doc-salary', 'kb-finance', 'Salary slips Jan–Sep.pdf', 'PDF', 1.6, 9, 18, 'INDEXED', { excerpt: ['Gross monthly salary, allowances and deductions for January to September 2026.'] }),
    D('doc-ins', 'kb-finance', 'Health insurance policy.docx', 'Word', 0.3, 22, 51, 'INDEXED', { excerpt: ['Section 4: Waiting period of 30 days from the policy start date, except for accidents.', 'Section 6: Room rent is capped at 1% of the sum insured per day.'] }),
    D('doc-budget', 'kb-finance', 'Budget 2026.md', 'Markdown', 0.01, null, 4, 'INDEXED', { excerpt: ['## Monthly targets\n- Rent 32%\n- Savings 25%\n- Food 14%'] }),
    D('doc-trip', 'kb-notes', 'Bangalore trip notes.txt', 'Text', 0.01, null, 5, 'INDEXED', { excerpt: ['Day 2: Lalbagh in the morning, then Church Street for lunch. Book the airport cab a day ahead.'] }),
    D('doc-meal', 'kb-notes', 'Weekly meal plan.md', 'Markdown', 0.01, null, 3, 'INDEXED', { excerpt: ['Mon: dal tadka + rice · Tue: paneer wraps · Wed: vegetable khichdi'] }),
    D('doc-receipt', 'kb-notes', 'scanned-receipt.pdf', 'PDF', 0.7, 1, 0, 'FAILED', { error: 'No text layer found (scanned image). OCR is planned for a later release.', excerpt: [] })
  ];
  // Pad KB doc counts so dashboard totals look realistic without listing every file.
  const kbExtra = { 'kb-research': { docs: 11, chunks: 214, mb: 28.1 }, 'kb-android': { docs: 29, chunks: 1248, mb: 12.7 }, 'kb-finance': { docs: 8, chunks: 286, mb: 5.6 }, 'kb-notes': { docs: 24, chunks: 140, mb: 1.8 } };

  const agents = [
    { id: 'ag-atlas', name: 'Atlas', template: 'research', role: 'Research Agent', desc: 'Answers questions from my ML papers with citations.', color: 'model', icon: 'search', model: null, tools: ['knowledge_search', 'document_reader', 'note_manager'], kbs: ['kb-research'], memory: { enabled: true, autoSave: false }, exec: { maxSteps: 8, maxTools: 6, timeout: 120, temp: 0.3 }, last: { status: 'completed', at: ago(130) }, prompt: templates[0].prompt, runs: 14 },
    { id: 'ag-archivist', name: 'Archivist', template: 'docanalyst', role: 'Knowledge Agent', desc: 'Pulls project constraints and decisions from the Android docs.', color: 'knowledge', icon: 'knowledge', model: null, tools: ['knowledge_search', 'document_reader'], kbs: ['kb-android'], memory: { enabled: false, autoSave: false }, exec: { maxSteps: 6, maxTools: 4, timeout: 90, temp: 0.2 }, last: { status: 'completed', at: ago(130) }, prompt: templates[3].prompt, runs: 6 },
    { id: 'ag-forge', name: 'Forge', template: 'coding', role: 'Coding Agent', desc: 'Kotlin and Compose help grounded in my project.', color: 'agent', icon: 'code', model: 'qwen2-5-coder-7b', tools: ['code_search', 'file_search', 'calculator'], kbs: ['kb-android'], memory: { enabled: true, autoSave: false }, exec: { maxSteps: 10, maxTools: 6, timeout: 180, temp: 0.2 }, last: { status: 'completed', at: ago(60 * 20) }, prompt: templates[1].prompt, runs: 31 },
    { id: 'ag-ledger', name: 'Ledger', template: 'docanalyst', role: 'Document Analyst', desc: 'Reads my lease, insurance and salary documents.', color: 'warn', icon: 'file', model: null, tools: ['document_reader', 'knowledge_search', 'calculator'], kbs: ['kb-finance'], memory: { enabled: true, autoSave: false }, exec: { maxSteps: 6, maxTools: 6, timeout: 120, temp: 0.1 }, last: { status: 'failed', at: ago(60 * 27), error: 'document_reader timed out on page 18–22 (5 s limit)' }, prompt: templates[3].prompt, runs: 9 },
    { id: 'ag-juno', name: 'Juno', template: 'assistant', role: 'Personal Assistant', desc: 'Everyday planning, notes and reminders of my preferences.', color: 'accent', icon: 'sparkle', model: 'llama-3-2-3b', tools: ['note_manager', 'calculator', 'knowledge_search'], kbs: ['kb-notes'], memory: { enabled: true, autoSave: true }, exec: { maxSteps: 6, maxTools: 4, timeout: 60, temp: 0.7 }, last: { status: 'completed', at: ago(60 * 30) }, prompt: templates[2].prompt, runs: 57 },
    { id: 'ag-sage', name: 'Sage', template: 'learning', role: 'Learning Assistant', desc: 'Quizzes me on quantisation and transformer papers.', color: 'warn', icon: 'star', model: null, tools: ['knowledge_search', 'note_manager'], kbs: ['kb-research'], memory: { enabled: true, autoSave: false }, exec: { maxSteps: 6, maxTools: 4, timeout: 90, temp: 0.5 }, last: { status: 'never', at: null }, prompt: templates[4].prompt, runs: 0 },
    { id: 'ag-reviewer', name: 'Critic', template: 'research', role: 'Review Agent', desc: 'Checks drafts against the request and the cited sources.', color: 'bad', icon: 'check', model: null, tools: ['knowledge_search'], kbs: [], memory: { enabled: false, autoSave: false }, exec: { maxSteps: 4, maxTools: 2, timeout: 60, temp: 0.1 }, last: { status: 'completed', at: ago(130) }, prompt: 'You are a strict reviewer. Compare the draft with the request and the sources. List concrete problems, or reply OK.', runs: 12 }
  ];

  const memories = [
    { id: 'm1', agent: 'ag-juno', kind: 'PREFERENCE', content: 'Prefers vegetarian recipes and plans meals for 2 people.', importance: 4, pinned: true, status: 'ACTIVE', created: ago(60 * 24 * 21), used: 18 },
    { id: 'm2', agent: 'ag-juno', kind: 'FACT', content: 'Lives in Bengaluru; usually commutes by metro (Purple line).', importance: 3, pinned: false, status: 'ACTIVE', created: ago(60 * 24 * 12), used: 7 },
    { id: 'm3', agent: 'ag-juno', kind: 'EPISODE', content: 'Planned a 3-day Bangalore itinerary for visiting parents (Oct 2026).', importance: 2, pinned: false, status: 'ACTIVE', created: ago(60 * 24 * 30), used: 2 },
    { id: 'm4', agent: 'ag-juno', kind: 'PREFERENCE', content: 'Wants answers short, with bullet points, no emojis.', importance: 5, pinned: true, status: 'ACTIVE', created: ago(60 * 24 * 40), used: 41 },
    { id: 'm5', agent: 'ag-juno', kind: 'FACT', content: 'Gym sessions are Tuesday and Friday at 7 am.', importance: 3, pinned: false, status: 'PROPOSED', created: ago(50), used: 0, from: 'Conversation “Weekly plan”' },
    { id: 'm6', agent: 'ag-forge', kind: 'PREFERENCE', content: 'Uses Kotlin 2.x, Compose and Hilt; avoids RxJava.', importance: 5, pinned: true, status: 'ACTIVE', created: ago(60 * 24 * 9), used: 22 },
    { id: 'm7', agent: 'ag-forge', kind: 'FACT', content: 'Project minSdk is 31 and only arm64-v8a is built.', importance: 4, pinned: false, status: 'ACTIVE', created: ago(60 * 24 * 6), used: 9 },
    { id: 'm8', agent: 'ag-forge', kind: 'EPISODE', content: 'Refactored InferenceScheduler to a priority queue with mutex (Nov 2026).', importance: 3, pinned: false, status: 'PROPOSED', created: ago(60 * 20), used: 0, from: 'Run “Scheduler refactor”' },
    { id: 'm9', agent: 'ag-atlas', kind: 'PREFERENCE', content: 'Wants every claim cited with the paper title, not just the chunk number.', importance: 4, pinned: false, status: 'ACTIVE', created: ago(60 * 24 * 4), used: 6 },
    { id: 'm10', agent: 'ag-ledger', kind: 'FACT', content: 'Monthly rent is 38,000 and the deposit paid was 76,000.', importance: 4, pinned: false, status: 'ACTIVE', created: ago(60 * 24 * 3), used: 3 }
  ];

  const conversations = [
    { id: 'c-lease', title: 'Lease deposit clause', model: 'qwen3-4b', agent: null, kbs: ['kb-finance'], updated: ago(35), system: 'You are a helpful assistant. Answer using the selected knowledge bases and cite sources.', agentMode: false,
      messages: [
        { role: 'user', text: 'When do I get my security deposit back, and how much is it?' },
        { role: 'ai', model: 'qwen3-4b', tps: 13.1, text: 'Your security deposit is **two months’ rent**, and it must be returned **within 30 days** of the tenancy ending, minus any documented deductions [chunk:7].\n\nFrom your saved budget, rent is 38,000, so the deposit is about **76,000**. If you plan to leave, remember the **60-day written notice** requirement [chunk:12].', sources: [{ n: 7, doc: 'doc-lease', title: 'Lease agreement — Flat 4B.pdf', where: 'p. 4 · Clause 7.2', score: 0.86 }, { n: 12, doc: 'doc-lease', title: 'Lease agreement — Flat 4B.pdf', where: 'p. 6 · Clause 9.1', score: 0.71 }] }
      ] },
    { id: 'c-flow', title: 'Kotlin Flow: debounce vs sample', model: 'qwen3-4b', agent: null, kbs: [], updated: ago(60 * 5), system: 'You are a senior Android engineer.', agentMode: false,
      messages: [
        { role: 'user', text: 'For streaming tokens into Compose, should I use debounce or sample?' },
        { role: 'ai', model: 'qwen3-4b', tps: 12.4, text: 'Use **`sample`** (or a fixed-interval conflation), not `debounce`.\n\n- `debounce` waits for a pause, so during fast streaming the UI would not update at all.\n- `sample(50)` emits the latest value every 50 ms, giving ~20 UI updates per second.\n\n```kotlin\nval uiText: StateFlow<String> = tokens\n    .runningFold(\"\") { acc, t -> acc + t }\n    .sample(50.milliseconds)\n    .stateIn(viewModelScope, SharingStarted.Eagerly, \"\")\n```\n\nEmit the final text without sampling when generation ends so the last tokens are never dropped.' }
      ] },
    { id: 'c-runtime', title: 'Compare runtimes + draft interface', model: 'qwen3-4b', agent: 'supervisor', kbs: ['kb-research', 'kb-android'], updated: ago(130), system: '', agentMode: true,
      messages: [
        { role: 'user', text: 'Compare llama.cpp and ExecuTorch for our app and draft a Kotlin interface for the inference engine.' },
        { role: 'ai', model: 'qwen3-4b', workflow: true, text: '**Recommendation:** start with llama.cpp for GGUF breadth and CPU performance; keep ExecuTorch as a later NPU backend [chunk:31].\n\nDraft interface prepared by Forge and reviewed by Critic. Open the workflow to see each agent’s contribution.', sources: [{ n: 31, doc: 'doc-arch', title: 'architecture.md', where: '§ Inference', score: 0.79 }] }
      ] },
    { id: 'c-photo', title: 'Photo: grocery receipt', model: 'smolvlm-500m', agent: null, kbs: [], updated: ago(18), system: 'You are a helpful assistant running locally on this device. Be concise.', agentMode: false,
      messages: [
        { role: 'user', text: 'How much did I spend, and on what?', photo: 'receipt' },
        { role: 'ai', model: 'smolvlm-500m', tps: 21.4, text: 'This is a **grocery receipt** from Fresh Mart, dated 14 Nov 2026.\n\n- Basmati rice 5 kg — 649\n- Toor dal 1 kg — 172\n- Milk 2 L — 112\n- Tomatoes 1 kg — 46\n\n**Total: 979** (paid by UPI).' },
        { role: 'user', text: 'Which item was the most expensive?' },
        { role: 'ai', model: 'smolvlm-500m', tps: 20.8, text: 'The **basmati rice (5 kg) at 649**, about two-thirds of the bill.' }
      ] },
    { id: 'c-trip', title: 'Packing list for Coorg', model: 'llama-3-2-3b', agent: 'ag-juno', kbs: ['kb-notes'], updated: ago(60 * 30), system: '', agentMode: false,
      messages: [
        { role: 'user', text: 'Make a short packing list for 2 days in Coorg in December.' },
        { role: 'ai', model: 'llama-3-2-3b', tps: 15.8, text: '- Light jacket (nights around 12–14 °C)\n- Walking shoes with grip for estate trails\n- Rain shell, just in case\n- Power bank and offline maps\n- Insect repellent' }
      ] }
  ];

  const toolLog = [
    { tool: 'knowledge_search', agent: 'ag-atlas', args: '{"query":"KV cache memory growth","k":6}', status: 'OK', ms: 84, at: ago(131) },
    { tool: 'document_reader', agent: 'ag-ledger', args: '{"document_id":"doc-ins","page_range":"18-22"}', status: 'TIMEOUT', ms: 5000, at: ago(60 * 27) },
    { tool: 'note_manager', agent: 'ag-juno', args: '{"action":"create","title":"Coorg packing"}', status: 'OK', ms: 12, at: ago(60 * 30), confirmed: true },
    { tool: 'sqlite_query', agent: 'ag-forge', args: '{"sql":"DELETE FROM notes"}', status: 'INVALID', ms: 1, at: ago(60 * 48), note: 'Rejected: only SELECT is allowed' },
    { tool: 'calculator', agent: 'ag-ledger', args: '{"expression":"38000*2"}', status: 'OK', ms: 1, at: ago(60 * 72) }
  ];

  const networkLog = [
    { host: 'huggingface.co', path: '/…/Phi-4-mini-instruct-Q4_K_M.gguf', bytes: '948 MB', at: ago(4), why: 'Model download' },
    { host: 'huggingface.co', path: '/…/gemma-3-4b-it-Q4_0.gguf', bytes: '1.68 GB', at: ago(60 * 2), why: 'Model download (paused)' },
    { host: 'catalog.local-ai.app', path: '/v1/catalog.json', bytes: '42 KB', at: ago(60 * 24), why: 'Catalog refresh (signed)' }
  ];

  // Document Studio exports (sample). md holds the document body used for previews.
  const exportsSeed = [
    { id: 'ex-leave', name: 'Leave request – 20 to 22 Nov.pdf', fmt: 'pdf', kb: 48, at: ago(60 * 3), md: '# Leave request\n\n**To:** Priya Raman, Engineering Manager  \n**Date:** 17 November 2026\n\nDear Priya,\n\nI would like to request **three days of leave from 20 to 22 November 2026** to attend a family function in Madurai.\n\n## Handover\n- Code reviews: Arjun\n- On-call: swapped with Meena (24–28 Nov)\n\nI will be reachable by phone for anything urgent.\n\nThank you,  \n[Your name]' },
    { id: 'ex-minutes', name: 'Sprint review minutes.docx', fmt: 'docx', kb: 21, at: ago(60 * 26), md: '# Sprint review — 12 Nov 2026\n\n**Attendees:** Priya, Arjun, Meena, Kiran\n\n## Decisions\n- Ship lite mode for 4 GB phones in 0.2\n- Move OCR to the next sprint\n\n## Action items\n\n| Owner | Action | Due |\n| --- | --- | --- |\n| Arjun | Vision model download sizes | 15 Nov |\n| Meena | Voice settings copy | 18 Nov |' }
  ];

  LA.seed = function () {
    const clone = (x) => JSON.parse(JSON.stringify(x));
    return {
      onboarded: false, look: 'dark', deviceId: '12', activeModel: 'qwen3-4b', modelLoaded: true,
      models: clone(models), downloads: clone(downloads), tools: clone(tools), kbs: clone(kbs), kbExtra: clone(kbExtra),
      documents: clone(documents), agents: clone(agents), memories: clone(memories), conversations: clone(conversations),
      toolLog: clone(toolLog), networkLog: clone(networkLog), exports: clone(exportsSeed),
      settings: { threads: 'auto', kvType: 'q8_0', gpu: false, wifiOnly: true, memoryApproval: true, indexOnCharge: false, appLock: false, voiceLang: '', speechRate: 1, readAloud: false },
      profiles: {}
    };
  };
  LA.devices = devices;
  LA.templates = templates;
})();
