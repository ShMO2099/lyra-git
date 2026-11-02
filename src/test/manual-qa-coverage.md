# Automated coverage for completed manual QA

Scope: the **47 executed feature families** in [the completed manual report](../../manual-test-artifacts/manual-qa-completed.md): 36 verified families and 11 families with recorded defects. The 43 pending families and nine blocked families are outside this addition. A completed manual test is not necessarily a passing test.

Three GPT-6 Luna agents inspected assertions and fixtures in `src/test/java`, covering the unit/helper, repository integration, and JavaFX UI layers. Tests using real repositories count as integration tests even when named `*Test`, rather than `*IT`. Existing tests were retained; missing operation and defect regressions were added.

## TDD follow-up

The original audit and its results below describe the stage before production fixes. Corrected regressions, additional controls and one-family-at-a-time fixes for the 11 recorded defects are tracked in [the TDD ledger](../../manual-test-artifacts/completed-defects-tdd.md). Refer to that ledger for current validation, rather than treating the initial failures below as current results.

## Initial audit validation status

**Compilation succeeded; only the 15 added test classes were selected for execution** after the user instructed “only run the changed tests.” The earlier instruction to defer builds was respected until that request. Two earlier compilation attempts had found test-source errors, which were corrected before this run.

Initial results: **39 cases, 26 passed, 13 unsuccessful (9 failures and 4 assertion errors), 0 skipped**. Those regressions reproduce the 11 recorded defect families; the results report explains which assertions were reached and the fixture rerun.

At this initial audit stage, production code and the Maven configuration were not changed. New defect tests assert the intended behavior; they are not disabled and do not assert that the bug should remain. The first run exposed submodule fixture problems: inherited Windows checkout line endings and local-file transport refusal in Git subprocesses. Only those new fixtures were corrected to use a temporary loopback Git daemon and committed LF attributes, then the two affected classes were rerun. See [initial execution results](../../manual-test-artifacts/automated-changed-tests-results.md) for counts, per-class outcomes and commands.

Detailed assertion-level audits and their boundaries:

- [Unit/helper audit](../../manual-test-artifacts/luna-unit-coverage-audit.md)
- [Repository integration audit](../../manual-test-artifacts/luna-integration-coverage-audit.md)
- [JavaFX UI audit](../../manual-test-artifacts/luna-ui-coverage-audit.md)

## Family mapping

This table identifies coverage locations, not executed results or a claim that every manual step has an automated equivalent. The detailed audits identify partial coverage and untested variants. A UI-only rendering/routing issue does not warrant a duplicate pure-unit test that cannot observe that behavior; its regression belongs in the real JavaFX harness. Conversely, remote history preservation needs a real repository oracle, rather than a mocked unit assertion.

Class names below refer to files under `java/com/jfx/git/`, normally in `core/` or `ui/`. “Added” identifies the additions from this task.

| Manual ID | Unit/helper or repository integration evidence | JavaFX UI / runtime evidence |
|---|---|---|
| R01 | `TabSessionTest`, `SwitchableGitBackendTest`, repository discovery in `BackendConformanceTest` | `LyraAppTest`, `LyraAppOpenTabTest` checks an opening repository is focused rather than duplicated; `AppShortcutsRobotTest` |
| R03 | `GitServiceCloneTest`, `NativeEngineSafetyTest` clone refusal and cancellation | **Added** `ManualQaCloneValidationTest`: required fields and Cancel |
| R05 | `PushTargetTest`, `RemoteConformanceTest`, first publication in `BackendConformanceTest` | `RepoToolbarRemoteRobotTest`: Push/Pull/Fetch, selected remote and tracking |
| R07 | **Added** `ManualCompletedIntegrationRemoteRecoveryTest.rejectedPushThenFetchMergeAndPushKeepsBothHistories` | Existing remote toolbar tests exercise real operations; the added integration flow checks recovery history and final publication |
| R08 | Remote/tag conformance; annotated tag object in `NativeGitBackendTest` | `RepoToolbarRemoteRobotTest`, `SidebarStashTagRobotTest` |
| R09 | Lease refusal/acceptance in `RemoteConformanceTest`, `BackendConformanceTest` | `RepoToolbarRemoteRobotTest` matching/stale lease cases |
| R10 | **Added** `ManualCompletedIntegrationRemoteRecoveryTest.fastForwardOnlyRefusesSafelyAndRebasePullKeepsLinearTopology` | `RepoToolbarRemoteRobotTest` FF-only refusal and Pull with rebase |
| R13 | `NativeGitBackendTest` detached push guard | Existing no-remotes toolbar test; **added** detached Push in `ManualQaGitOperationControlsRobotTest` |
| R15 | `RemoteConformanceTest` fetch continues despite one broken remote | `RepoToolbarRobotTest.fetchReachesTheRealBackendAndReportsARealFailure` |
| R23 | `SubmoduleTest`; **added** staged/unstaged edit preservation in `ManualCompletedIntegrationSubmoduleTest` | `SidebarSubmoduleRobotTest` add, init/update and dirty-deinit refusal |
| B01 | Backend checkout/stash conformance | `SidebarBranchRobotTest` real checkout and stash/reapply of dirty work |
| B04 | Branch creation/deletion in `GitServiceTest`; merged classification in backend conformance | `SidebarBranchRobotTest.deletingAMergedBranchNeedsOnlyOneConfirmation` |
| B07 | Backend merge mode, parent-count, squash and fast-forward conformance | `MergeAndStashOptionsRobotTest` ordinary merge and squash |
| B09 | Backend merge conflict, resolved state and Abort preservation | `MergeConflictRobotTest` ours/theirs resolution and saved draft |
| B12 | `InteractiveRebaseTest`, backend replay/abort conformance | `InteractiveRebaseRobotTest` reword, squash and reorder |
| B13 | `BackendConformanceTest.aConflictingRebaseStepCanBeSkipped` | **Added** conflicting interactive rebase Skip in `ManualQaGitOperationControlsRobotTest` |
| B17 | Backend cherry-pick continuation/abort and `MultiCommitOperationsTest` | Existing `CherryPickRobotTest`; **added** conflict Skip in `ManualQaGitOperationControlsRobotTest` |
| B20 | `GitServiceTest.revertCreatesAnInverseCommit`, merge-revert conformance | **Added** graph Revert action with history/tree checks in `ManualQaGitOperationControlsRobotTest` |
| B22 | Reset behavior in `GitServiceTest`, native revision validation | **Added** soft/mixed/hard reset, hard Cancel and accept in `ManualQaGitOperationControlsRobotTest` |
| B23 | `ActionStackTest` undo/redo and real rebase undo | Existing toolbar undo/redo; **added** merge-tip restoration in `ManualQaGitOperationControlsRobotTest` |
| B27 | `GitFlowTest` setup/start/finish refs and content | `GitFlowSidebarRobotTest` setup, feature lifecycle, Cancel |
| B29 | `WorktreeTest`, native worktree listing | `SidebarWorktreeRobotTest` add, lock/unlock, remove and prune |
| B32 | Native sparse apply/disable and cross-engine state | `SidebarSparseCheckoutRobotTest` actual Apply/Disable controls |
| E01 | `HunkSelectionTest`, `HunkStagingTest`, hunk/backend conformance | `CommitPanelRobotTest`, `DiffViewerRenderTest` file/hunk/line staging |
| E04 | Sign-off/backend conformance, amend in `GitServiceTest`, `JGitCommitParityTest` | `CommitPanelRobotTest` commit, sign-off and amend |
| E06 | Backend named/untracked stash and apply, `GitServiceTest` Pop | `RightPanelComposerRobotTest`, `MergeAndStashOptionsRobotTest`, `SidebarStashTagRobotTest` |
| E09 | Directional comparison in `MultiCommitOperationsTest` | `ComparisonSwapTest` reverse and restore comparison |
| E11 | `CommitSearchTest`, backend message/path/content search | `HistorySearchRobotTest`, `SearchInFilesRobotTest` |
| E13 | Backend file history/rename/blame, restore bytes in `GitServiceTest` | `FileHistoryEndToEndRobotTest` historical text and blame; `EditHistoricalFileRobotTest` historical editing |
| E16 | `FileEditorTest` real writes and unstaged state | `DiffEditorEditingTest`; saved-buffer staging is additionally covered by the new E17 regression |
| E18 | `FileEditorTest` encoding/write behavior | Existing `ExternalEditBeforeSaveTest`; **added** `ManualQaExternalEditWarningTest`: Cancel, Compare, accept disk version and index preservation |
| E22 | `ImageDiffViewTest` image metadata/side state | **Added** `ManualQaHistoricalImageComparisonTest`: File History parent/selected pixels differ from the working copy |
| E24 | `TextEncodingTest`, `FileEditorTest` Unicode round trips | `MarkdownViewTest` rendering and relative paths; historical Unicode-specific display remains a narrower manual variant |
| E26 | `WorkspaceFilesTest`, `RepositoryWatcherTest` paths/notifications | `WorkspaceTreeTallyTest`, `SearchInFilesRobotTest`, `ExternalChangeRobotTest` |
| U01 | `SettingsTest` persistence where applicable | `ZoomRobotTest`, `AppShortcutsRobotTest` palette opening |
| U12 | **Added** `PackagedRuntimeIntegrationTest`: shipped reduced JDK, dependencies, native library, real graph/history/diff and Askpass launcher | `LyraAppTest`, `DiffViewerRenderTest`; packaged executable main-window interaction remains manual evidence |
| R06 | **Added** `GitExecutableEnvironmentTest`: preserve inherited Windows `Path` as one case-insensitive environment variable | This isolates the environment defect; it does not authenticate over hosted HTTPS |
| R14 | Runner error delivery in `GitTaskRunnerErrorTest` | **Added** `ManualQaFailureSummaryTest`: actual Git cause in summary and complete output in Details |
| R22 | **Added** same-instance Hybrid deinit/reinit clean status and graph in `ManualCompletedIntegrationSubmoduleTest` | Existing `SidebarSubmoduleRobotTest`; **added** `ManualQaSubmoduleRefreshRobotTest`: same-tab graph and staging remain clean through deinit/reinit |
| B11 | `OperationStateTest`, backend resolved-but-unfinished state | **Added** resolved rebase conflict count and Continue enablement in `ManualQaOperationControlsRobotTest` |
| B16 | Rebase state/continuation backend tests | **Added** `ManualQaOperationControlsRobotTest`: stopped rebase must not offer ordinary “Commit merge” |
| B31 | Sparse backend apply/restore tests | **Added** `ManualQaSparseFolderChoicesRobotTest`: offer unchanged tracked folders too |
| E02 | Backend stage-all/index conformance | **Added** `ManualQaRightPanelRegressionRobotTest`: empty Staged placeholder updates lists and Commit availability |
| E07 | Backend untracked stash storage and restore | **Added** `ManualQaRightPanelRegressionRobotTest`: inspector includes third-parent untracked path |
| E17 | `FileEditorTest` save bytes | **Added** `ManualQaBinaryFileViewTest.saveAndStageStillStagesWhenTheEditorBufferWasAlreadySaved` |
| E21 | Historical blob/backend diff tests | **Added** `ManualQaHistoricalImageRobotTest`: main commit-details Before/After image pixels |
| E23 | `DiffSummaryTest`, native commit-side raw bytes | **Added** `ManualQaBinaryFileViewTest.binaryEncodingInFileViewShowsTheFileBytesRatherThanItsPatch` |

## Initial additions inventory

This inventory describes the initial audit additions. The TDD follow-up expands the relevant classes and adds `TopLevelDirectoriesAtCommitTest`; its final selected cases and rerun command are in [the TDD ledger](../../manual-test-artifacts/completed-defects-tdd.md).

**28 new test method definitions in 15 classes**: one unit test, four repository integration methods, 21 source UI methods, and two packaged-runtime integration methods. Three repository methods are parameterized, so the invocation count varies with available engines. These are source counts, not pass counts.

| Added class | Methods | Purpose |
|---|---:|---|
| [GitExecutableEnvironmentTest](java/com/jfx/git/core/GitExecutableEnvironmentTest.java) | 1 | Preserve Windows inherited `Path` |
| [ManualCompletedIntegrationRemoteRecoveryTest](java/com/jfx/git/core/ManualCompletedIntegrationRemoteRecoveryTest.java) | 2 | Rejected push recovery and divergent FF-only/rebase Pull |
| [ManualCompletedIntegrationSubmoduleTest](java/com/jfx/git/core/ManualCompletedIntegrationSubmoduleTest.java) | 2 | Preserve prior edits during add; clean same-instance Hybrid deinit/reinit |
| [ManualQaCloneValidationTest](java/com/jfx/git/ui/ManualQaCloneValidationTest.java) | 2 | Required fields and Cancel in the real clone form |
| [ManualQaGitOperationControlsRobotTest](java/com/jfx/git/ui/ManualQaGitOperationControlsRobotTest.java) | 6 | Rebase/cherry-pick Skip, Revert, reset modes/Cancel, merge Undo/Redo, detached Push |
| [ManualQaSubmoduleRefreshRobotTest](java/com/jfx/git/ui/ManualQaSubmoduleRefreshRobotTest.java) | 1 | Same-tab clean graph/staging after deinit/reinit |
| [ManualQaFailureSummaryTest](java/com/jfx/git/ui/ManualQaFailureSummaryTest.java) | 2 | Useful failure cause and complete Details output |
| [ManualQaOperationControlsRobotTest](java/com/jfx/git/ui/ManualQaOperationControlsRobotTest.java) | 2 | Resolved conflict refresh and correct rebase composer action |
| [ManualQaRightPanelRegressionRobotTest](java/com/jfx/git/ui/ManualQaRightPanelRegressionRobotTest.java) | 2 | Empty Staged action refresh and untracked stash inspection |
| [ManualQaSparseFolderChoicesRobotTest](java/com/jfx/git/ui/ManualQaSparseFolderChoicesRobotTest.java) | 1 | All tracked folder choices regardless of last changed folder |
| [ManualQaBinaryFileViewTest](java/com/jfx/git/ui/ManualQaBinaryFileViewTest.java) | 2 | Actual binary blob bytes and stage after prior Save |
| [ManualQaHistoricalImageRobotTest](java/com/jfx/git/ui/ManualQaHistoricalImageRobotTest.java) | 1 | Main commit-details historical image sources |
| [ManualQaHistoricalImageComparisonTest](java/com/jfx/git/ui/ManualQaHistoricalImageComparisonTest.java) | 1 | File History historical image sources |
| [ManualQaExternalEditWarningTest](java/com/jfx/git/ui/ManualQaExternalEditWarningTest.java) | 1 | Outside write warning, Cancel and Compare reconciliation |
| [PackagedRuntimeIntegrationTest](java/com/jfx/git/PackagedRuntimeIntegrationTest.java) | 2 | Shipped runtime/dependencies/graph/diff and Askpass launcher |

## Execution notes

Run only one Maven process at a time, as required by the repository README. The new tests use JUnit 5 and the existing TestFX/Monocle headless configuration. Repository tests create disposable `@TempDir` fixtures and local bare remotes; they do not write to the manually tested repositories or use hosted credentials. The submodule fixtures additionally require installed native Git for their temporary daemon, bound to `127.0.0.1` on an ephemeral port and stopped during teardown.

`PackagedRuntimeIntegrationTest` needs Windows and an existing packaged image, configured with `-Dlyra.test.packagedImage=<absolute image directory>`. It skips when no default image exists; an explicitly configured missing image fails. It does not build an image. Its child probe is compiled during that test and uses the image's own runtime. The Askpass case checks a missing-bridge refusal, not successful hosted authentication. Both packaged tests ran against the existing image from the manual-testing fixture folder.

Native-Git tests require Git to be installed. Cross-engine parameterized tests exercise available engines, so the number of invocations depends on the host. Environment prerequisites are not evidence that a feature passed. Headless source UI tests cannot prove native folder choosers, real desktop focus, hosted authentication, or the packaged main executable's interactive startup.
