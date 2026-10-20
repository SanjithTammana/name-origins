# name origins

Type a first name and see where it comes from: the languages it passed through, oldest first, with the word
each language used and what it meant. Michael comes out as Biblical Hebrew מִיכָאֵל ("who is like God?") →
Ancient Greek → Latin → Old French → Middle English → English.

Everything is Java. The data is built by Java programs on a PC, and the page itself is Java compiled to
JavaScript with [TeaVM](https://teavm.org/), so it runs as a static site (GitHub Pages) with no server.

## What happens when you type a name

The matcher tries four layers in order and the page says which one answered:

1. **Exact.** The name is in the data (case and accents ignored).
2. **Spelling variant.** Not recorded as typed, but a spelling that sounds the same is. A sound key built for
   transliterated names merges th/t, ph/f, ee/i, c/k, doubled letters and a silent final h, so *Sanjith*,
   *Sanjit* and *Sanjeet* share the key `sanjit`. Recorded variant spellings (Wiktionary "alternative forms")
   are grouped with union-find, so *Mykal* finds *Michael*.
3. **Guess.** No variant either: a character n-gram naive Bayes model guesses the origin from letter patterns
   and shows the recorded names behind the guess ("-jit: Abhijit, Ranjit"). It is labelled as a guess.
4. **Nothing confident.** The closest recorded spellings (BK-tree over edit distance).

Two meaning features sit on top: **names with a similar meaning** under every result, and **search by
meaning** ("victory" finds Victoria, Niké, Zafer, Nasr, Voitto, Lanakila, Jaya...). Both use a 50/50 blend of TF-IDF and
word embeddings trained from scratch in Java (word2vec, skip-gram with negative sampling) on Wiktionary
definitions.

## How well it works

Numbers from the last build (`work/eval-origin.txt`, `work/eval-meaning.txt`):

| Part | Measure | Result |
|---|---|---|
| Origin guess | top-1 accuracy, 2,716 held-out names, 31 origins | 0.555 (always guessing the largest origin: 0.129) |
| Origin guess | top-3 accuracy | 0.761 |
| Origin guess | above the confidence floor | shown for 78% of names, right 65% of the time |
| Meaning search | concept recall@50 (TF-IDF / embeddings / blend) | 0.961 / 0.955 / **0.973** |
| Meaning search | shared-root recall@10 | 0.599 / 0.586 / **0.609** |
| Meaning search | names found only through a synonym, recall@100 | 0.000 / 0.135 / 0.088 |

The blend was chosen by a rule fixed before the results were seen: it had to raise concept recall without
lowering shared-root recall by more than 0.05. The last row is a diagnostic added afterwards (the precision
measure saturated at 1.0 for every method); it shows what embeddings add that TF-IDF cannot.

Known limits: origins are only as good as Wiktionary's etymologies; many names, especially from Indian,
Chinese and African languages, have no etymology recorded and fall through to the guess; the meaning blend
can match on a shared word rather than a shared idea ("full victory" sits near "full moon").

## Data structures

| Question | Structure | Cost |
|---|---|---|
| exact name, prefix | sorted key array + binary search | O(L log N), O(L log N + k) |
| sounds like | hash map: sound key → names | O(L) + bucket |
| variant groups | union-find (path compression, union by rank), run offline | ~O(1) per link |
| nearest spelling | BK-tree with a banded, early-exit edit distance | visits a small fraction of N |
| origin guess | sparse map: n-gram → (origin, bonus) pairs | O(L · origins) |
| meaning | inverted index + size-k min-heap; flat 8-bit vector array | O(postings), O(M · d) |

Names live in parallel arrays rather than one object each. Indexes needed only for misspelled names are built
in the background after the page loads. On a desktop the page is usable about 0.7 s after opening, and lookups
take 4–50 ms.

## Building

Needs a JDK (21 or newer). The Maven wrapper fetches everything else.

```
./mvnw test          # unit tests, then compiles the page into target/site
```

`target/site` is the whole site; serve it with any static server, e.g. `jwebserver -d target/site`.

Rebuilding the data (only needed to refresh it; the generated files are committed in `site/data`) takes about
20 minutes and the kaikki.org dump `raw-wiktextract-data.jsonl.gz` (~2.9 GB) from
<https://kaikki.org/dictionary/rawdata.html>:

```
./mvnw compile exec:java -Dexec.args="path/to/raw-wiktextract-data.jsonl.gz"
```

## Layout

```
src/main/java/nameorigins/core    shared by the build tools and the page: matcher, indexes, models
src/main/java/nameorigins/build   offline data tools (filter, index, origin model, word2vec, meaning)
src/main/java/nameorigins/web     the page (TeaVM entry point, rendering)
site/                             index.html, styles.css, generated data/
```

## Credits and licences

Code: MIT, see `LICENSE`. Data in `site/data` and the test fixtures are derived from
[Wiktionary](https://en.wiktionary.org/) (CC BY-SA 4.0) via [kaikki.org](https://kaikki.org/); see
`DATA_LICENSE.md`.
