/* Smoke test for a libgit2 build: calls every function Libgit2Bindings looks up, against a
 * repository it creates, and reports each call. Exits non-zero if any fails.
 *
 *   cc -I libgit2/include -o smoke-test smoke-test.c path/to/libgit2.so
 *   ./smoke-test /tmp/smoke-repo                 (the directory must not exist yet) */
#include <git2.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static int failures = 0;

#define CHECK(call) do { \
    int rc_ = (call); \
    if (rc_ < 0) { \
        const git_error *e_ = git_error_last(); \
        printf("FAIL %s -> %d (%s)\n", #call, rc_, e_ ? e_->message : "no message"); \
        failures++; \
    } else printf("ok   %s\n", #call); \
} while (0)

static void write_file(const char *path, const char *text) {
    FILE *f = fopen(path, "w");
    if (!f) { perror(path); exit(2); }
    fputs(text, f);
    fclose(f);
}

static int count_status(const char *path, unsigned int flags, void *payload) {
    (void) flags;
    printf("     status: %s\n", path);
    (*(int *) payload)++;
    return 0;
}

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : "/tmp/smoke-repo";
    char file[512], file2[512];
    snprintf(file, sizeof file, "%s/a.txt", dir);
    snprintf(file2, sizeof file2, "%s/b.txt", dir);

    int major, minor, rev;
    printf("init count %d\n", git_libgit2_init());
    git_libgit2_version(&major, &minor, &rev);
    printf("libgit2 %d.%d.%d\n", major, minor, rev);

    /* git_error_last after a failure must carry a message. */
    git_repository *missing = NULL;
    if (git_repository_open(&missing, "/nonexistent/repo") < 0 && git_error_last()
            && git_error_last()->message && *git_error_last()->message)
        printf("ok   git_error_last: %s\n", git_error_last()->message);
    else { printf("FAIL git_error_last\n"); failures++; }

    /* Set up: a repository with one commit (these calls are not bound, only scaffolding). */
    git_repository *repo = NULL;
    CHECK(git_repository_init(&repo, dir, 0));
    write_file(file, "one\n");
    git_index *idx = NULL;
    git_oid tree_id, commit_id;
    git_tree *tree = NULL;
    git_signature *sig = NULL;
    CHECK(git_repository_index(&idx, repo));
    CHECK(git_index_add_bypath(idx, "a.txt"));
    CHECK(git_index_write(idx));
    CHECK(git_index_write_tree(&tree_id, idx));
    CHECK(git_tree_lookup(&tree, repo, &tree_id));
    CHECK(git_signature_new(&sig, "Smoke", "smoke@example.com", 1700000000, 0));
    CHECK(git_commit_create_v(&commit_id, repo, "HEAD", sig, sig, NULL, "first", tree, 0));
    git_index_free(idx);
    git_tree_free(tree);
    git_signature_free(sig);
    git_repository_free(repo);

    /* The bound calls, in the order the backend uses them. It opens with open_ext and no
     * flags, which searches upward from the path, so open from a subdirectory. */
    char sub[512];
    snprintf(sub, sizeof sub, "%s/sub", dir);
    if (mkdir(sub, 0755) != 0) { perror(sub); return 2; }
    repo = NULL;
    CHECK(git_repository_open_ext(&repo, sub, 0, NULL));
    const char *found = repo ? git_repository_workdir(repo) : NULL;
    printf("     open_ext from sub/ found workdir=%s\n", found ? found : "(none)");
    if (!found || strncmp(found, dir, strlen(dir)) != 0 || strstr(found, "/sub")) {
        printf("FAIL open_ext did not find the enclosing repository\n");
        failures++;
    }
    git_repository_free(repo);

    repo = NULL;
    CHECK(git_repository_open(&repo, dir));
    printf("     workdir=%s path=%s\n", git_repository_workdir(repo), git_repository_path(repo));

    git_reference *head = NULL;
    CHECK(git_repository_head(&head, repo));
    printf("     head shorthand=%s\n", git_reference_shorthand(head));
    git_reference_free(head);

    write_file(file, "one\ntwo\n");
    write_file(file2, "new\n");
    git_status_options opts;
    CHECK(git_status_options_init(&opts, GIT_STATUS_OPTIONS_VERSION));
    opts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
    opts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED;
    int n = 0;
    CHECK(git_status_foreach_ext(repo, &opts, count_status, &n));
    if (n != 2) { printf("FAIL expected 2 status entries, got %d\n", n); failures++; }

    CHECK(git_repository_index(&idx, repo));
    CHECK(git_index_add_bypath(idx, "a.txt"));
    CHECK(git_index_add_bypath(idx, "b.txt"));
    CHECK(git_index_remove_bypath(idx, "b.txt"));
    CHECK(git_index_write(idx));
    CHECK(git_index_read(idx, 1));
    git_index_free(idx);

    git_object *target = NULL;
    CHECK(git_revparse_single(&target, repo, "HEAD"));
    char *paths[] = { "a.txt" };
    git_strarray spec = { paths, 1 };
    CHECK(git_reset_default(repo, target, &spec));
    git_object_free(target);

    /* After the reset a.txt is modified in the worktree only, b.txt untracked. */
    n = 0;
    CHECK(git_status_foreach_ext(repo, &opts, count_status, &n));
    if (n != 2) { printf("FAIL expected 2 status entries after reset, got %d\n", n); failures++; }

    git_repository_free(repo);
    printf("shutdown count %d\n", git_libgit2_shutdown());
    printf(failures ? "SMOKE FAILED (%d)\n" : "SMOKE PASSED\n", failures);
    return failures ? 1 : 0;
}
