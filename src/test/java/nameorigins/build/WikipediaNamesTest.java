package nameorigins.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WikipediaNamesTest {
    @Test
    void inlineInfoboxAndLeadGiveAnExplicitMeaning() {
        String wiki = "{{Infobox Given name|name=Chinedu|gender=Male|language=Igbo|meaning=God leads}}\n"
                + "'''Chinedu''' is a common [[Igbo people|Igbo]] masculine given name and surname. "
                + "It means “God leads”.<ref>citation</ref>\n\n== People ==\n* A person";
        WikipediaNames.Fact fact = WikipediaNames.extract("Chinedu", "Chinedu", wiki);
        assertEquals("God leads", fact.meaning());
        assertTrue(fact.summary().contains("Igbo masculine given name"), fact.summary());
        assertFalse(fact.summary().contains("People"), fact.summary());
    }

    @Test
    void aSourcedLeadWorksWithoutAnInfobox() {
        String wiki = "'''Thandiwe''' is a given name of [[Nguni languages|Nguni]] origin which means "
                + "\"beloved\".<ref>source</ref> Notable people with the name include:\n\n== References ==";
        WikipediaNames.Fact fact = WikipediaNames.extract("Thandiwe", "Thandiwe", wiki);
        assertEquals("beloved", fact.meaning());
        assertTrue(fact.summary().contains("Nguni origin"), fact.summary());
    }

    @Test
    void possibleJapaneseWritingsAreNotCalledOneDefinitiveMeaning() {
        String wiki = "{{Infobox Given Name Revised|name=Haruto|meaning=It can have many different meanings "
                + "depending on the [[kanji]] used.|origin=Japanese}}\n"
                + "'''Haruto''' is a masculine Japanese given name.\n\n== Written forms ==\n"
                + "Haruto can be written using different kanji characters and can mean:\n"
                + "*春人, \"spring, person\"\n*陽斗, \"sun/yang, dipper\"\n== People ==";
        WikipediaNames.Fact fact = WikipediaNames.extract("Haruto", "Haruto", wiki);
        assertEquals("", fact.meaning());
        assertTrue(fact.summary().contains("Possible writings include 春人"), fact.summary());
    }

    @Test
    void aLinkedMythFigureOrDisambiguationIsNotANameSource() {
        assertNull(WikipediaNames.extract("Arash", "Arash", "{{Infobox royalty|name=Arash}}\n"
                + "'''Arash the Archer''' is a figure of Iranian mythology."));
        assertNull(WikipediaNames.extract("Yui", "Yui", "'''Yui''' may refer to:\n\n== People ==\n* Yui (name)"));
        assertNull(WikipediaNames.extract("Pher", "Christopher", "{{Infobox given name|name=Christopher|meaning=Christ-bearer}}\n"
                + "'''Christopher''' is a masculine given name."));
    }

    @Test
    void aLinkedMeaningDoesNotStopAtTheLinksPipe() {
        String wiki = "{{Infobox given name\n|name=Dariush\n|meaning=Holding firm [[Asha|the good]]\n"
                + "|origin=[[Persian language|Persian]]\n}}\n'''Dariush''' is a Persian given name.";
        assertEquals("Holding firm the good", WikipediaNames.extract("Dariush", "Dariush", wiki).meaning());
    }
}
