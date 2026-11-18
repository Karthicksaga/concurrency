// Regression test for the 0.1.17 crash: texts longer than multilingual-e5-small's 511 positions aborted in get_rows.
// Run after run.sh has built the library: java -cp build/classes EmbedTest build/cmake/bin path/to/embedder.gguf
import com.localai.app.runtime.inference.LlamaNative;
public class EmbedTest {
    public static void main(String[] a) {
        System.load(a[0] + "/liblocalai.so");
        LlamaNative n = new LlamaNative(); n.init(a[0]);
        System.out.println("dim " + n.loadEmbedder(a[1], 4));
        StringBuilder ta = new StringBuilder(), en = new StringBuilder(), mix = new StringBuilder();
        while (ta.length() < 1464) ta.append("இந்த ஒப்பந்தம் வாடகைதாரர் மற்றும் உரிமையாளர் இடையே செய்யப்பட்டது. ");
        while (en.length() < 1464) en.append("The tenant shall pay rent on the first day of every month. ");
        while (mix.length() < 3000) mix.append("₹12,500/- 😀 ௧௨௩ ").append("‍‌").append("डेटा ");
        String[][] cases = { {"tamil 1464", "passage: " + ta.substring(0, 1464)}, {"english 1464", "passage: " + en.substring(0, 1464)},
            {"mixed 3000", "passage: " + mix}, {"long 20000", "passage: " + en.toString().repeat(14)} };
        for (String[] c : cases) { float[][] v = n.embed(new String[]{c[1]}); System.out.println("OK " + c[0] + " -> " + (v[0] == null ? "null" : v[0].length)); }
    }
}
