# Release notes page

Before every release the owner gets one page with three parts: the player-facing patch notes, an in-game test list
where each test is marked **Pass / Issue / Fail** with a note, and what the release changes on live. `template.html`
is the 1.4.0 page with its content swapped for `{{PLACEHOLDERS}}`. Its CSS and script are unchanged.

## Making the page for a release

1. Copy the template to a scratch folder, outside the repo: `slumsms-<version>-notes.html`.
2. Fill it in:
   - `{{VERSION}}` (e.g. `1.4.1`), and `{{VERSION_SLUG}}` with the dots removed (`141`). The slug keys this
     browser's fallback copy, so each release starts clean.
   - **Header chips:** the test server branch, the test client, the server test count, and the version live runs now.
   - **Patch notes:** one `<h3>` per area. Each `li` is one change in player words, tagged `New` or `Fix`. Put
     tooling and ops under "Behind the scenes", and anything held back or still broken under "Known issues".
   - **Test list:** one `section.group[data-group]` per feature, with an empty `<small>` in its `h3` (it shows
     "n of m"). Each test is a `.check` holding a checkbox with a unique id `t-<group>-<n>`, a title span, and an
     optional `.how` span. Write the exact commands (`!item <id>`, `!givenx`, `!event start <name>`) and the
     result you expect. The script turns each checkbox into the Pass/Issue/Fail buttons and a note box.
   - **`!qa` line:** when the test has a `!qa` scenario (`!qa list` on the test server, or
     `docs/ops/qa-suite.md`), start its `.how` with `<code>!qa run <id></code>`, for example
     `!qa run fm-1 — then …`. It stages the test and logs it to `logs/qa/<id>-<time>.log` on the test server, which
     Claude reads when you mark the test. Then write the manual steps that are left. The 1.4.1 ids are:
     bk-1/bk-2, fm-1/fm-2, np-1, np-2, np-3 (`!qa do close` replaces the restart), gh-1/gh-2, ld-1, wd-1, wd-2,
     gn-2 (alias sc-1), gd-1/gd-2 and cs-2. Tests without a scenario (cp-*, cs-1, cs-3, ln-*, gn-1) keep only their
     manual steps.
   - **Release:** migrations, config keys, WZ data steps (for example `tools/data-align/*.tsv` with their dry-run
     counts), the catalog size, the client files, the rehearsal result, and the deploy, rollback and publish
     commands from `/opt/mapleroot-deployments/v<version>/`.
3. Check that nothing is left over and the ids are unique:
   ```sh
   grep -n '{{' slumsms-<version>-notes.html                               # must print nothing
   grep -o 'id="t-[^"]*"' slumsms-<version>-notes.html | sort | uniq -d   # must print nothing
   ```
4. Publish it as a new artifact. Use a new page for each release, so results from different releases never mix.
   Declare the `db` and `user` capabilities.
   - With `db`, results save to the shared store (collection `qa`, one document per test id: `status`, `note`,
     `test`, `updatedAt`), so the owner's marks reach Claude. Read them back with `ArtifactData` (`list` on `qa`).
   - Without `db`, the page still works, but marks stay in that one browser only (`localStorage`).
   - With `user`, a viewer without write access sees the results read-only.

## Rules

- **Test ids are the database keys.** Never rename or reuse one for a different test once the owner has started
  marking. To add a test, give it a new id.
- **Mark only what the build really contains.** If something is held back, say so under "Known issues" and drop its
  tests, or note in its `.how` line that it is expected to fail.
- **No secrets on the page.** Keys, passwords and the patch server's access key stay out.

The 1.4.0 page this template comes from is `slumsms-1.4.0-notes.html`, in the session scratchpad where it was written.
