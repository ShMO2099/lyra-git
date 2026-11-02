# Automated coverage for incomplete manual QA

For subsequent B11 live-operation fixes, see [the completed-defect TDD ledger](../../manual-test-artifacts/completed-defects-tdd.md). The remaining audit results below retain their original execution scope and dates.

Scope: all **52 families** in [the incomplete manual handoff](../../manual-test-artifacts/manual-qa-incomplete.md): 43 pending families and the nine blocked families in its appendix. This task adds automated coverage; it does not resume desktop manual testing or change those historical manual outcomes.

Three GPT-6 Luna agents inspected existing assertion bodies and fixtures by domain. Existing unit/helper, repository integration and real JavaFX tests are mapped separately from new additions and remaining qualifications. Repository tests can be named `*Test` and still be integration tests. A UI-only interaction does not require an artificial pure-unit test that cannot observe it.

Detailed family maps:

- [Remote and application families: 21 IDs](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md)
- [Branch and history families: 17 IDs](../../manual-test-artifacts/luna-incomplete-branches-audit.md)
- [Editor and workspace families: 14 IDs](../../manual-test-artifacts/luna-incomplete-editor-audit.md)

## Validation

Added **41 test methods in 26 new classes**. Compilation succeeded. Latest selected results: **42 cases, 38 passed, 4 failed, 0 errors, 0 skipped**. See [execution results and failure evidence](../../manual-test-artifacts/automated-incomplete-tests-results.md).

The four failures expose three product behaviors: stale conflict-completion controls (two cases), annotated-tag Undo recreating a lightweight tag, and palette recency not being applied when reopened. Desired-behavior assertions remain enabled. Production code and the Maven configuration were not changed.

The four `IncompleteQaSshTransportIntegrationTest` cases passed. Its fixture uses temporary SSH configuration, known-host records and synthetic keys, with JGit pack handlers on an embedded SSH server. It exercises the application's JGit clone/push/fetch/pull calls under that isolated transport configuration, plus rejected client identity and unknown/changed server keys. It does not prove the desktop host-trust prompt, hosted permissions, personal agent setup or all-engine SSH parity.

Only newly added classes from this incomplete-coverage task were selected. The earlier completed-coverage additions and all existing test classes were excluded from execution. Maven compiles test sources before selecting classes. No packaging or manual desktop QA was run. New fixtures use synthetic local repositories, isolated app settings and loopback services; they do not perform hosted writes or change user-global Git identities/credentials.

## How to read the audit

An existing test with an assertion for the relevant behavior is coverage, even when it is one part of a larger suite. Merely opening a form is not proof that its fields reach the operation. Mock provider responses can establish request/UI behavior but cannot establish hosted authentication or account permissions. New UI tests use the existing TestFX/Monocle or FxHarness configuration and compare real repository/file state where the action mutates it.

The original handoff describes many variants within each family. These audits distinguish the central behavior, newly covered gaps, unoffered actions, native chooser/desktop boundaries and external-service qualifications. A passing automated case does not imply that every manual step or every environment has passed. Failing desired-behavior assertions are retained as regressions and reported without disabling them or changing production code.

## Added test inventory

| New class | Method definitions |
|---|---:|
| [IncompleteBranchCleanupSelectionTest](java/com/jfx/git/ui/views/IncompleteBranchCleanupSelectionTest.java) | 1 |
| [IncompleteBranchNameValidationTest](java/com/jfx/git/core/IncompleteBranchNameValidationTest.java) | 2 |
| [IncompleteQaActivityLogControlsTest](java/com/jfx/git/ui/IncompleteQaActivityLogControlsTest.java) | 1 |
| [IncompleteQaBranchNamesRobotTest](java/com/jfx/git/ui/IncompleteQaBranchNamesRobotTest.java) | 3 |
| [IncompleteQaBranchOperationsRobotTest](java/com/jfx/git/ui/IncompleteQaBranchOperationsRobotTest.java) | 4 |
| [IncompleteQaBranchTagUndoRobotTest](java/com/jfx/git/ui/IncompleteQaBranchTagUndoRobotTest.java) | 1 |
| [IncompleteQaCloneDepthValidationRobotTest](java/com/jfx/git/ui/IncompleteQaCloneDepthValidationRobotTest.java) | 1 |
| [IncompleteQaCommandPaletteRobotTest](java/com/jfx/git/ui/IncompleteQaCommandPaletteRobotTest.java) | 2 |
| [IncompleteQaCommitChecksRobotTest](java/com/jfx/git/ui/IncompleteQaCommitChecksRobotTest.java) | 1 |
| [IncompleteQaDiffPreferencesTest](java/com/jfx/git/ui/settings/IncompleteQaDiffPreferencesTest.java) | 1 |
| [IncompleteQaDirtyPullRobotTest](java/com/jfx/git/ui/IncompleteQaDirtyPullRobotTest.java) | 1 |
| [IncompleteQaEditorEncodingTest](java/com/jfx/git/ui/IncompleteQaEditorEncodingTest.java) | 1 |
| [IncompleteQaEditorNavigationRobotTest](java/com/jfx/git/ui/IncompleteQaEditorNavigationRobotTest.java) | 3 |
| [IncompleteQaHistorySearchRobotTest](java/com/jfx/git/ui/IncompleteQaHistorySearchRobotTest.java) | 1 |
| [IncompleteQaImageDisplayTest](java/com/jfx/git/ui/IncompleteQaImageDisplayTest.java) | 1 |
| [IncompleteQaLfsPointerTest](java/com/jfx/git/core/IncompleteQaLfsPointerTest.java) | 1 |
| [IncompleteQaProfileMenuRobotTest](java/com/jfx/git/ui/IncompleteQaProfileMenuRobotTest.java) | 1 |
| [IncompleteQaPullAbortRobotTest](java/com/jfx/git/ui/IncompleteQaPullAbortRobotTest.java) | 1 |
| [IncompleteQaRemoteUrlsAndPruneIntegrationTest](java/com/jfx/git/core/IncompleteQaRemoteUrlsAndPruneIntegrationTest.java) | 2 |
| [IncompleteQaRepositoryInitChoicesRobotTest](java/com/jfx/git/ui/dialogs/IncompleteQaRepositoryInitChoicesRobotTest.java) | 1 |
| [IncompleteQaRepositoryIsolationTest](java/com/jfx/git/ui/IncompleteQaRepositoryIsolationTest.java) | 2 |
| [IncompleteQaSearchControlsRobotTest](java/com/jfx/git/ui/IncompleteQaSearchControlsRobotTest.java) | 1 |
| [IncompleteQaSshTransportIntegrationTest](java/com/jfx/git/core/IncompleteQaSshTransportIntegrationTest.java) | 3 |
| [IncompleteQaWorkspaceRobotTest](java/com/jfx/git/ui/IncompleteQaWorkspaceRobotTest.java) | 1 |
| [IncompleteQaWorktreeProtectionTest](java/com/jfx/git/core/IncompleteQaWorktreeProtectionTest.java) | 2 |
| [IncompleteRevertIntegrationTest](java/com/jfx/git/core/IncompleteRevertIntegrationTest.java) | 2 |

## All 52 manual families

Statuses below are the historical manual handoff status. Automated results do not change those manual outcomes. The linked audit supplies exact existing assertion methods, layers and remaining boundaries.

| Family | Manual status | Assertion audit | New coverage |
|---|---|---|---|
| R02 — Initialize a repository and starter templates | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaRepositoryInitChoicesRobotTest` |
| R11 — Pull conflict, Abort and completion | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaPullAbortRobotTest` |
| R12 — Dirty Pull guard and auto-stash recovery | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaDirtyPullRobotTest` |
| R16 — Remote configuration and upstream picker | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaRemoteUrlsAndPruneIntegrationTest` |
| R17 — Remote branch checkout, merge and deletion | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaRemoteBranchMenuRobotTest` (JGit); every engine in `RefusalConformanceTest`, `AutoStashTest` and the `BackendConformanceTest` remote-checkout pair |
| R18 — Fetch all, multiple remotes and pruning | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaRemoteUrlsAndPruneIntegrationTest` |
| R21 — Disconnected provider guidance | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| B05 — Remaining linked-branch deletion guard and forced deletion | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaWorktreeProtectionTest` |
| B06 — Gone-upstream cleanup, after merged cleanup passed | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteBranchCleanupSelectionTest` |
| B08 — Independent divergent merge and custom message | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchOperationsRobotTest` |
| B10 — Use ours, Use both, manual result and multiple conflicts | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchOperationsRobotTest` |
| B18 — Conflicting cherry-pick Abort and Continue | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchOperationsRobotTest` |
| B19 — Multi-pick order and merge-parent selection | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | Existing coverage; see audit |
| B21 — Older/merge revert and conflicting revert recovery | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteRevertIntegrationTest`; `IncompleteQaGraphRevertRobotTest`, `IncompleteQaMergeRevertRobotTest` (each with a git-CLI subclass) |
| B24 — Reflog preview/Restore and dirty Undo | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchHistoryRobotTest` (JGit), `IncompleteQaBranchHistoryNativeRobotTest` (git CLI) |
| B28 — Git Flow start and genuine feature/release/hotfix finishes | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | Existing coverage; see audit; `IncompleteQaGitFlowFinishStashRobotTest` (JGit); every engine in `GitFlowTest` |
| B30 — Locked/dirty/duplicate worktrees and pruning | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaWorktreeProtectionTest` |
| B33 — Bisect start, endpoints, candidate decisions, reset and script | Pending | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | Existing coverage; see audit |
| E03 — Stage all/Unstage all, selected lines and discard controls | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | Existing coverage; see audit |
| E05 — Commit validation, staged amend, co-author, hooks and precommit checks | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaCommitChecksRobotTest` |
| E08 — Apply/Drop stash, keep index, selected-only and branch recovery | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | Existing coverage; see audit |
| E10 — Diff layout, context, whitespace, change navigation and minimap | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | Existing coverage; see audit |
| E12 — Author/hash search, stale queries and real pagination | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaHistorySearchRobotTest` |
| E14 — File history across rename and historical hunk reversal | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaRenameHistoryRobotTest` (with a git-CLI subclass); `IncompleteQaRenamedFileRestoreRobotTest` (with a git-CLI subclass) |
| E15 — Search in files: text, regex, case, glob and revision scope | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaSearchControlsRobotTest` |
| E19 — Other overwrite choices, navigation/restart drafts and missing files | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaEditorNavigationRobotTest` |
| E20 — Preserve UTF-16 BOM, CRLF and UTF-8 while editing | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaEditorEncodingTest` |
| E25 — Image controls and binary/encoding alternatives | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaImageDisplayTest` |
| E27 — Workspace create/delete/ignore path, extension and folder | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaWorkspaceRobotTest` |
| E28 — Snapshot capture, inspect, restore and deletion | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaSnapshotFidelityRobotTest` (with a git-CLI subclass) |
| E29 — Copy/Save/Apply patches and error/cancellation | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | Existing coverage; see audit |
| E30 — LFS setup, track/untrack, fetch/pull/prune | Pending | [editor](../../manual-test-artifacts/luna-incomplete-editor-audit.md) | `IncompleteQaLfsPointerTest` |
| U02 — Remaining palette recency and tab lifecycle | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaCommandPaletteRobotTest` |
| U03 — Graph Hide/Show/Solo, selection and ref dragging | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U04 — Activity log scopes, filters, copy/export and Clear | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaActivityLogControlsTest` |
| U05 — General/Appearance/Diff/Commit/External Tools preferences | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaDiffPreferencesTest` |
| U06 — Engine selection and representative manual parity | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U07 — Repository-local settings, identity and conflict prevention | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U08 — Named profiles, local application and profile tabs | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaProfileMenuRobotTest` |
| U09 — SSH status, test-key generation and credential controls | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U10 — External editor/diff/merge/terminal integration | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U11 — Remaining shortcuts and update-check presentation | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
| U13 — Independent windows/tabs and closing while busy | Pending | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaRepositoryIsolationTest`; `IncompleteQaTabRoutingRobotTest` |
| R04 — Clone variants, target/depth validation and transfer cancellation | Blocked | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaCloneDepthValidationRobotTest` |
| B02 — New branch entry and validation | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteBranchNameValidationTest`, `IncompleteQaBranchNamesRobotTest` |
| B03 — Rename a branch and reject invalid/duplicate names | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteBranchNameValidationTest`, `IncompleteQaBranchNamesRobotTest` |
| B14 — Change interactive-plan actions using normal human input | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | Existing coverage; see audit |
| B15 — Older-commit Reword, preserving actual Drop/Move/Squash passes | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | Existing coverage; see audit |
| B25 — Actual tag Undo and exact annotated-object preservation | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchTagUndoRobotTest` |
| B26 — New tag/name validation and independently untested remote deletion | Blocked | [branches](../../manual-test-artifacts/luna-incomplete-branches-audit.md) | `IncompleteQaBranchNamesRobotTest` |
| R19 — SSH transport, host-key and authentication session | Blocked | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | `IncompleteQaSshTransportIntegrationTest` |
| R20 — Connected provider repositories, requests/issues and reviews | Blocked | [remotes-apps](../../manual-test-artifacts/luna-incomplete-remotes-apps-audit.md) | Existing coverage; see audit |
