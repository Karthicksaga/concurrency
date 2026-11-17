package com.localai.app.runtime.inference;

// Host-side mirror of the Kotlin `object LlamaNative` (same JNI symbols and signatures).
public class LlamaNative {
    public interface Callback {
        void onToken(byte[] utf8);
        void onDone(int promptTokens, int reusedTokens, int generatedTokens, long promptMs, long genMs);
    }
    public native void init(String nativeLibDir);
    public native String systemInfo();
    public native int loadModel(String path, int nCtx, int nThreads, int nBatch, boolean kvQ8);
    public native String modelInfo();
    public native void unload();
    public native void abort();
    public native int generate(String[] roles, String[] contents, int maxTokens, float temp, float topP, int topK,
                               float minP, float repeatPenalty, int seed, boolean enableThinking, String grammar, Callback cb);
    public native String schemaToGrammar(String jsonSchema);
    public native int loadEmbedder(String path, int nThreads);
    public native void unloadEmbedder();
    public native float[][] embed(String[] texts);
}
