# Lyra

A desktop git client: each repository opens in a tab with a commit graph, a staging area and a diff viewer.

## Language

### Diffs

**Loaded diff**:
One file's diff as the app has read it: decoded in the chosen encoding, split into hunks, together with what the diff says about the file as a whole. Choosing another encoding or reloading gives a new loaded diff.
_Avoid_: diff text, diff bytes, file diff, current diff

**Diff kind**:
What a loaded diff compares: an unstaged change (index against working tree), a staged change (HEAD against index), a conflict, a commit, a stash, a comparison or a snapshot. The kind decides which hunk actions the diff offers.
_Avoid_: sides, diff type, mode

**Hunk action**:
Staging, unstaging, discarding or reverting one hunk, or the selected lines of one.
_Avoid_: hunk button, patch action

**Discard**:
Throwing away uncommitted changes in the working tree: a file's, a hunk's or some lines'.
_Avoid_: revert (for uncommitted work), reset

**Revert**:
Undoing a committed change by applying its opposite. Reverting a commit makes a new commit; reverting a hunk leaves an uncommitted edit in the working tree.
_Avoid_: discard (for committed changes), undo

### Editing

**Editing session**:
The built-in editor's hold on one file, from Edit to Done: the buffer, the text it believes is on disk, and whether the last save got there. It edits the working copy wherever there is one: a revision on screen is swapped for the working copy first, under the file's new name if it was renamed since. A file no longer on disk is edited as the revision shows it, and saving recreates it; with no working tree to read at all, what is on screen is what is edited. Saving always writes into the working tree, and never ends the session.
_Avoid_: edit mode

**Draft**:
An editing session's unsaved buffer, parked outside the repository while the user types so a crash cannot take it, and offered back the next time that file's working copy is opened.
_Avoid_: autosave, backup

### Committing

**Commit composer**:
The commit box: the message being written, the options that go with it (Amend, Skip hooks, Sign off) and what its Commit button says committing will do. It fills in by itself only over an empty box: the branch's issue key, the commit template, the message git prepared, HEAD's message for an amend.
_Avoid_: commit form, commit panel

**Commit request**:
One press of the Commit button as the commit composer saw it: the message, the options, whether to push afterwards and whether to stage everything first.
_Avoid_: commit options, commit command

### Refs

**Current branch**:
The branch HEAD points at. There is none while HEAD is detached; what the user reads then names the commit instead.
_Avoid_: head branch, active branch

**Ref snapshot**:
The repository's refs as one read saw them: the current branch, branches, tags, upstreams, stashes and remote names, shared by every view that refresh feeds.
_Avoid_: ref cache, ref list

**Remote-tracking branch**:
A remote's branch as this clone last fetched it, named `<remote>/<branch>`. Its remote is the longest configured remote name it starts with - a remote's own name may contain a slash, so `team/core/topic` is `topic` on `team/core` - and its branch part is what follows. A remote's symbolic `HEAD` (`origin/HEAD`) only points at one of them and is not one.
_Avoid_: remote ref (for the branch), origin branch

### History

**History window**:
The part of a repository's history the graph has loaded: the page shown, how far back it was walked, and whether older history follows. A refresh, loading older commits and a reveal each replace the page; a shorter page that lands after a longer one is dropped.
_Avoid_: graph limit, paging state, loaded rows

**Reveal**:
Selecting a commit, stash or branch tip in the graph, paging older history into the history window first when it lies beyond it. A newer reveal supersedes an older one.
_Avoid_: page in (for the whole gesture), scroll to

**Hide and Solo**:
The user's say over which refs the graph is walked from: refs hidden from it, or one ref soloed so that only its history and HEAD's are shown. Solo wins over Hide, and neither applies to the current branch. Both are kept by full ref name - a local `main`, `origin/main` and a tag `main` are three choices - and follow a branch or a remote when it is renamed. A stash is hidden by its commit, and has no solo.
_Avoid_: filter (a filter narrows what is listed, not where the walk starts), show/hide state

### Operations

**Operation**:
A change to the repository the user starts in a tab - a checkout, a commit, a discard - from the click to the tab redrawn after it: run on the repository's lane, shown in the status bar while it runs, given one verdict in the activity log once it is over, and followed by the refresh it asked for. Several git steps with questions between them - auto-stash's, a cherry-pick's follow-up rebase - can make one operation.
_Avoid_: action, job, task (a task is one piece of work on a lane)

**Refusal**:
Git declining an operation for a reason the app can act on: local changes in the way, a branch not fully merged, a diverged branch, local modifications, or an input that names something wrong.
_Avoid_: error, rejection (git's word for a refused push)

**Auto-stash**:
Stashing local changes around an operation that they would block, and putting them back afterwards.
_Avoid_: set aside (except in the message the user reads)
