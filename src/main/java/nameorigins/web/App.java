package nameorigins.web;

import java.util.ArrayList;
import java.util.List;
import nameorigins.core.MatchResult;
import nameorigins.core.MeaningSearch;
import nameorigins.core.NameEntry;
import nameorigins.core.Scored;
import nameorigins.core.Text;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.html.HTMLInputElement;
import org.teavm.jso.dom.xml.Element;

/**
 * Browser entry point, compiled to JavaScript by TeaVM. Wires the page to the matcher: the URL hash holds the
 * traced name (#Sophia), so results can be linked and the back button works.
 */
public final class App {
    private static final Data DATA = new Data();
    private static final HTMLDocument DOC = HTMLDocument.current();
    private static HTMLInputElement nameInput;
    private static HTMLInputElement meaningInput;
    private static HTMLElement result;
    private static HTMLElement status;
    private static HTMLElement meaningResult;
    private static HTMLElement nameList;
    private static int traceSeq;
    private static int meaningSeq;

    private App() {}

    @JSBody(params = "s", script = "return encodeURIComponent(s);")
    private static native String encode(String s);

    @JSBody(params = "s", script = "try { return decodeURIComponent(s); } catch (e) { return s; }")
    private static native String decode(String s);

    public static void main(String[] args) {
        nameInput = DOC.getElementById("name").cast();
        meaningInput = DOC.getElementById("meaning").cast();
        result = DOC.getElementById("result");
        status = DOC.getElementById("status");
        meaningResult = DOC.getElementById("meaning-result");
        nameList = DOC.getElementById("name-list");

        DOC.getElementById("trace-form").addEventListener("submit", (EventListener<Event>) e -> {
            e.preventDefault();
            String name = nameInput.getValue().trim();
            if (!name.isEmpty()) {
                Window.current().getLocation().setHash(encode(name));
            }
        });
        DOC.getElementById("meaning-form").addEventListener("submit", (EventListener<Event>) e -> {
            e.preventDefault();
            searchMeaning(meaningInput.getValue().trim());
        });
        nameInput.addEventListener("input", (EventListener<Event>) e -> suggest(nameInput.getValue()));
        // Any [data-name] button (suggestions, similar names, spellings) traces that name.
        EventListener<Event> chipClick = e -> {
            Element target = e.getTarget().cast();
            String name = target.getAttribute("data-name");
            if (name != null && !name.isEmpty()) {
                nameInput.setValue(name);
                Window.current().getLocation().setHash(encode(name));
                nameInput.focus();
            }
        };
        result.addEventListener("click", chipClick);
        meaningResult.addEventListener("click", chipClick);
        Window.current().addEventListener("hashchange", (EventListener<Event>) e -> traceFromHash());

        status.setInnerText("Loading names…");
        DATA.loadCore(() -> {
            status.setInnerText("");
            traceFromHash();
            DATA.warmUp();
        }, err -> status.setInnerText("The name data didn’t load (" + err + "). Try reloading the page."));
    }

    private static void traceFromHash() {
        String hash = Window.current().getLocation().getHash();
        if (hash.startsWith("#")) {
            hash = hash.substring(1);
        }
        String name = decode(hash).trim();
        if (name.isEmpty() || DATA.matcher == null) {
            return;
        }
        nameInput.setValue(name);
        trace(name);
    }

    private static void trace(String name) {
        int seq = ++traceSeq; // drop late answers if the visitor has already typed another name
        MatchResult r = DATA.matcher.match(name);
        String key = switch (r) {
            case MatchResult.Exact e -> e.key();
            case MatchResult.Variant v -> v.key();
            case MatchResult.Inferred i -> null;
            case MatchResult.None n -> null;
        };
        if (key == null) {
            render(seq, r, List.of(), List.of());
            return;
        }
        DATA.entries(key, entries -> DATA.similar(key, similar -> render(seq, r, entries, similar)));
    }

    private static void render(int seq, MatchResult r, List<NameEntry> entries, List<String> similar) {
        if (seq != traceSeq) {
            return;
        }
        result.setInnerHTML(View.result(r, entries, similar));
        DOC.setTitle(r.query() + " · name origins");
    }

    /** Autocomplete: up to 8 recorded names starting with what has been typed (no animation: it fires per key). */
    private static void suggest(String typed) {
        String f = Text.fold(typed);
        if (DATA.dir == null || f.length() < 2) {
            nameList.setInnerHTML("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (int slot : DATA.dir.withPrefix(f, 40)) {
            if (DATA.dir.hasOrigin(slot) && added < 8) {
                sb.append("<option value=\"").append(Html.esc(DATA.dir.display(slot))).append("\"></option>");
                added++;
            }
        }
        nameList.setInnerHTML(sb.toString());
    }

    private static void searchMeaning(String text) {
        if (text.isEmpty() || DATA.dir == null) {
            return;
        }
        int seq = ++meaningSeq; // a slower, older search must not overwrite a newer one (or its error)
        meaningResult.setInnerText("Loading meanings…");
        DATA.meaning((MeaningSearch search) -> {
            if (seq != meaningSeq) {
                return;
            }
            List<String> names = new ArrayList<>();
            for (Scored<String> s : search.search(text, 16)) {
                names.add(s.item());
            }
            meaningResult.setInnerHTML(View.meaningResults(text, DATA.displays(names.toArray(new String[0]))));
        }, err -> {
            if (seq == meaningSeq) {
                meaningResult.setInnerText("The meaning data didn’t load. Try again.");
            }
        });
    }
}
