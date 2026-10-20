package nameorigins.web;

import java.util.function.Consumer;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.dom.html.HTMLDocument;

/**
 * Fetches a text file relative to the page. Appends the build id stamped into index.html (data-build), so a
 * returning visitor never mixes a new name directory with cached shard files from an older build.
 */
final class Http {
    private Http() {}

    private static final String BUILD = HTMLDocument.current().getDocumentElement().getAttribute("data-build");

    static void get(String url, Consumer<String> ok, Consumer<String> failed) {
        XMLHttpRequest xhr = new XMLHttpRequest();
        xhr.open("GET", BUILD == null || BUILD.isEmpty() ? url : url + "?v=" + BUILD);
        xhr.onLoad(e -> {
            if (xhr.getStatus() == 200) {
                ok.accept(xhr.getResponseText());
            } else {
                failed.accept(url + " returned " + xhr.getStatus());
            }
        });
        xhr.onError(e -> failed.accept("could not load " + url));
        xhr.send();
    }
}
