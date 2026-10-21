# Name result feedback

Reports start as public [GitHub issues](https://github.com/SanjithTammana/name-origins/issues) using the
`Report this result` link beneath a name lookup. GitHub sign-in is required. The issue form asks for the
name, the result that looks wrong, the expected result if known, and a source. Reporters should leave out
private details.

When the issue is opened, `.github/workflows/index-feedback.yml` writes one JSON pointer under
`feedback/reports/`, named by issue number. Each pointer contains only the checked name, result category,
creation date, and issue URL. The full report stays in the issue. No report changes the name data or trains
a model automatically.

To audit: open the pointer's issue, reproduce the result, inspect its Wiktionary/Wikidata/Wikipedia source
and the matching layer, then classify the cause (source error, extraction, variant match, model guess, or
missing coverage). Record the evidence and resolution in the issue. Correct the source pipeline or matcher
only after that audit, and close the issue when verified.

The workflow requires GitHub Actions `contents: write` permission on the default branch. If branch rules
block its commit, the public issue still holds the report; inspect the workflow failure and index it later.
