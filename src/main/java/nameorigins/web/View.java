package nameorigins.web;

import java.util.List;
import nameorigins.core.MatchResult;
import nameorigins.core.NameEntry;
import nameorigins.core.OriginModel;
import nameorigins.core.OriginStep;
import nameorigins.core.Scored;

/**
 * Turns results into HTML. Pure Java with no browser calls, so it is unit-tested on the JVM.
 * All data is escaped (Html.esc) because it comes from Wiktionary edits and from what the visitor types.
 */
final class View {
    private View() {}

    /**
     * @param entries entries of the matched key (Exact/Variant), empty otherwise
     * @param similar display names with a similar meaning (may be empty)
     */
    static String result(MatchResult r, List<NameEntry> entries, List<String> similar) {
        StringBuilder sb = new StringBuilder();
        switch (r) {
            case MatchResult.Exact e -> sb.append(entries.isEmpty() ? notLoaded(e.query()) : entriesHtml(entries));
            case MatchResult.Variant v -> {
                String shown = Html.esc(entries.isEmpty() ? v.key() : entries.get(0).display());
                sb.append("<p class=\"note\">“").append(Html.esc(v.query()));
                if (v.recorded()) {
                    sb.append("” is recorded, but with no origin. The same name spelled <strong>").append(shown)
                            .append("</strong> has one.");
                } else {
                    sb.append("” isn’t recorded as typed. The closest recorded spelling is <strong>").append(shown)
                            .append("</strong>.");
                }
                if (!v.alternatives().isEmpty()) {
                    sb.append(" Other spellings: ").append(chips(v.alternatives(), "inline-chips"));
                }
                sb.append("</p>");
                sb.append(entries.isEmpty() ? notLoaded(v.key()) : entriesHtml(entries));
            }
            case MatchResult.Inferred i -> sb.append(inferredHtml(i));
            case MatchResult.None n -> {
                sb.append("<p class=\"note\">There’s no record of “").append(Html.esc(n.query())).append("” yet.");
                if (!n.suggestions().isEmpty()) {
                    sb.append(" Closest recorded names:</p>").append(chips(n.suggestions(), "chips"));
                } else {
                    sb.append("</p>");
                }
            }
        }
        sb.append(feedbackHtml(r));
        if (!similar.isEmpty()) {
            sb.append("<section class=\"similar\" aria-labelledby=\"similar-h\"><h3 id=\"similar-h\">Names with a similar meaning</h3>")
                    .append(chips(similar, "chips")).append("</section>");
        }
        return sb.toString();
    }

    private static String feedbackHtml(MatchResult result) {
        return "<aside class=\"feedback\" aria-label=\"Feedback about this result\">"
                + "<p>Does this look wrong or incomplete?</p><a href=\""
                + Html.esc(reportUrl(result))
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">Report this result</a>"
                + "<p class=\"feedback-note\">Opens a public GitHub form. Sign-in is required; leave out private details.</p>"
                + "</aside>";
    }

    /** Issue-form field IDs match .github/ISSUE_TEMPLATE/name-feedback.yml. */
    static String reportUrl(MatchResult result) {
        String type = switch (result) {
            case MatchResult.Exact e -> "Recorded entry";
            case MatchResult.Variant v -> "Spelling variant";
            case MatchResult.Inferred i -> "Letter-pattern guess";
            case MatchResult.None n -> "No result";
        };
        String name = result.query().trim();
        int points = name.codePointCount(0, name.length());
        if (points > 80) name = name.substring(0, name.offsetByCodePoints(0, 80));
        return "https://github.com/SanjithTammana/name-origins/issues/new?template=name-feedback.yml&name="
                + encodeQuery(name) + "&result_type=" + encodeQuery(type);
    }

    /** Exact and Variant keys always have entries in the data, so none means the shard did not download. */
    private static String notLoaded(String name) {
        return "<p class=\"note\">The details for “" + Html.esc(name) + "” didn’t load. Check your connection and"
                + " trace it again.</p>";
    }

    private static String entriesHtml(List<NameEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(entryHtml(entries.get(0)));
        if (entries.size() > 1) {
            sb.append("<details class=\"more\"><summary>Also recorded in ").append(entries.size() - 1)
                    .append(entries.size() == 2 ? " other language" : " other languages").append("</summary>");
            for (int i = 1; i < entries.size(); i++) {
                sb.append(entryHtml(entries.get(i)));
            }
            sb.append("</details>");
        }
        return sb.toString();
    }

    static String entryHtml(NameEntry e) {
        StringBuilder sb = new StringBuilder("<article class=\"entry\">");
        sb.append("<header class=\"entry-head\"><h2 class=\"entry-name\">").append(Html.esc(e.display())).append("</h2>")
                .append("<p class=\"entry-meta\">").append(Html.esc(e.language()));
        if (!e.gender().isEmpty()) {
            sb.append(e.language().isEmpty() ? "" : " · ").append(Html.esc(e.gender())).append(" name");
        }
        sb.append("</p></header>");
        if (!e.chain().isEmpty()) {
            sb.append("<ol class=\"lineage\" aria-label=\"Where the name came from, oldest first\">");
            for (OriginStep s : e.chain()) {
                sb.append("<li class=\"step\"><span class=\"step-lang\">").append(Html.esc(s.language())).append("</span>");
                if (!s.form().isEmpty()) {
                    sb.append("<span class=\"step-form\">").append(Html.esc(s.form())).append("</span>");
                }
                sb.append("</li>");
            }
            sb.append("<li class=\"step step-now\"><span class=\"step-lang\">").append(Html.esc(e.language()))
                    .append("</span><span class=\"step-form\">").append(Html.esc(e.display())).append("</span></li></ol>");
        }
        if (!e.meaning().isEmpty()) {
            sb.append("<p class=\"meaning\"><span class=\"label\">Meaning</span> “").append(Html.esc(e.meaning())).append("”</p>");
        }
        if (!e.summary().isEmpty()) {
            sb.append("<p class=\"summary\">").append(Html.esc(e.summary())).append("</p>");
        }
        if (!e.wikipediaTitle().isEmpty()) {
            sb.append("<p class=\"source\"><a href=\"https://en.wikipedia.org/wiki/")
                    .append(Html.esc(encodeQuery(e.wikipediaTitle().replace(' ', '_'))))
                    .append("\">Wikipedia article</a></p>");
        } else if (e.chain().isEmpty() && e.summary().startsWith("Listed on Wikidata")) {
            sb.append("<p class=\"source\"><a href=\"https://www.wikidata.org/w/index.php?search=")
                    .append(Html.esc(encodeQuery(e.display()))).append("\">Wikidata</a></p>");
        } else {
            sb.append("<p class=\"source\"><a href=\"https://en.wiktionary.org/wiki/").append(Html.esc(wikiTitle(e.display())))
                    .append('#').append(Html.esc(e.language().replace(' ', '_'))).append("\">Wiktionary entry</a></p>");
        }
        return sb.append("</article>").toString();
    }

    private static String wikiTitle(String display) {
        return display.replace(' ', '_');
    }

    /** Percent-encodes characters that would break a query string (URLEncoder is not in TeaVM's class library). */
    static String encodeQuery(String s) {
        // whole string at once: a character outside the BMP is two chars, and one alone encodes as "?"
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean asciiAlnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (asciiAlnum || c == '-' || c == '.' || c == '_') {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static String inferredHtml(MatchResult.Inferred i) {
        StringBuilder sb = new StringBuilder("<article class=\"entry inferred\">");
        sb.append("<header class=\"entry-head\"><h2 class=\"entry-name\">").append(Html.esc(i.query())).append("</h2>")
                .append("<p class=\"entry-meta\">Not recorded yet: a guess from its letter patterns, not a record</p></header>");
        boolean usageShown = false;
        sb.append("<ol class=\"odds\">");
        for (Scored<String> o : i.origins()) {
            if (o.score() < 0.01) {
                continue; // "Persian 0%" says nothing
            }
            usageShown |= OriginModel.isUsage(o.item());
            sb.append("<li><span class=\"odds-lang\">").append(Html.esc(o.item())).append("</span><span class=\"odds-pct\">")
                    .append(percent(o.score())).append("</span></li>");
        }
        sb.append("</ol>");
        if (usageShown) {
            sb.append("<p class=\"note\">“Used in” predicts where a name is recorded, not where it originated.</p>");
        }
        if (!i.evidence().isEmpty()) {
            sb.append("<p class=\"label\">Recorded names with the same patterns</p><ul class=\"evidence\">");
            for (String line : i.evidence()) {
                sb.append("<li>").append(Html.esc(line)).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</article>");
        if (!i.suggestions().isEmpty()) {
            sb.append("<p class=\"note\">Close recorded names:</p>").append(chips(i.suggestions(), "chips"));
        }
        return sb.toString();
    }

    static String percent(double p) {
        return p >= 0.995 ? "over 99%" : Math.round(p * 100) + "%";
    }

    /** Clickable names; App listens for clicks on [data-name] and traces that name. */
    static String chips(List<String> names, String cls) {
        StringBuilder sb = new StringBuilder("<ul class=\"").append(cls).append("\">");
        for (String n : names) {
            sb.append("<li><button type=\"button\" class=\"chip\" data-name=\"").append(Html.esc(n)).append("\">")
                    .append(Html.esc(n)).append("</button></li>");
        }
        return sb.append("</ul>").toString();
    }

    static String meaningResults(String query, List<String> names) {
        if (names.isEmpty()) {
            return "<p class=\"note\">No names with a meaning close to “" + Html.esc(query) + "”.</p>";
        }
        return chips(names, "chips");
    }
}
