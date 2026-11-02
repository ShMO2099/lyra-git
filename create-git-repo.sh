#!/bin/bash

# Git Repository Generator
# This script creates a test Git repository with complex branching and merge patterns
# - many branches, merges and criss-crossings - for looking at the commit graph by hand.
#
# Nothing runs this. It is a developer aid for manual testing, not part of the build and
# not used by any test: the automated suites build their own fixtures in @TempDir so they
# stay hermetic. It lived under src/main/resources until it was noticed that everything
# there is packaged into the shipped jar, where an executable script nobody calls invites
# a reader to go looking for the code that calls it.
#
# Run it from a scratch directory: it creates ./test-git-repo, removing any previous one.

set -e

# Repository name
REPO_NAME="test-git-repo"

# Remove existing repository if it exists
if [ -d "$REPO_NAME" ]; then
    echo "Removing existing repository..."
    rm -rf "$REPO_NAME"
fi

# Create and initialize repository
echo "Creating repository: $REPO_NAME"
mkdir "$REPO_NAME"
cd "$REPO_NAME"
git init

# Configure Git user (change these as needed)
git config user.name "Test User"
git config user.email "test@example.com"

# Function to create commit with specific author and date
create_commit() {
    local hash_prefix="$1"
    local message="$2"
    local author="$3"
    local days_ago="$4"
    local branch="$5"
    
    # Calculate date (days ago from now)
    local commit_date=$(date -d "$days_ago days ago" '+%Y-%m-%d %H:%M:%S')
    
    # Make sure we're on the right branch
    git checkout -B "$branch" 2>/dev/null || git checkout "$branch" 2>/dev/null || true
    
    # Create some file changes for the commit
    echo "Commit: $message" >> "changes_$hash_prefix.txt"
    echo "Author: $author" >> "changes_$hash_prefix.txt"
    echo "Date: $commit_date" >> "changes_$hash_prefix.txt"
    echo "---" >> "changes_$hash_prefix.txt"
    
    git add .
    
    # Set author and commit with specific date
    GIT_AUTHOR_NAME="$author" \
    GIT_AUTHOR_EMAIL="$author@example.com" \
    GIT_COMMITTER_NAME="$author" \
    GIT_COMMITTER_EMAIL="$author@example.com" \
    GIT_AUTHOR_DATE="$commit_date" \
    GIT_COMMITTER_DATE="$commit_date" \
    git commit -m "$message"
    
    echo "Created commit: $message (by $author, $days_ago days ago)"
}

# Function to create merge commit
create_merge() {
    local message="$1"
    local author="$2"
    local days_ago="$3"
    local target_branch="$4"
    local source_branch="$5"
    
    local commit_date=$(date -d "$days_ago days ago" '+%Y-%m-%d %H:%M:%S')
    
    git checkout "$target_branch"
    
    GIT_AUTHOR_NAME="$author" \
    GIT_AUTHOR_EMAIL="$author@example.com" \
    GIT_COMMITTER_NAME="$author" \
    GIT_COMMITTER_EMAIL="$author@example.com" \
    GIT_AUTHOR_DATE="$commit_date" \
    GIT_COMMITTER_DATE="$commit_date" \
    git merge --no-ff "$source_branch" -m "$message"
    
    echo "Created merge: $message (merging $source_branch into $target_branch)"
}

echo "Creating initial commit history..."

# Initial commits on main
create_commit "a1b2c3d" "Initial commit" "Alice" 61 "main"
echo "# Test Git Repository" > README.md
git add README.md
git commit --amend --no-edit

create_commit "b2c3d4e" "Add project structure" "Bob" 60 "main"
mkdir -p src/main/java src/test/java
echo "Project structure created" > src/main/java/.gitkeep
git add .

create_commit "c3d4e5f" "Setup build system" "Charlie" 59 "main"
echo "build.gradle" > build.gradle
echo "settings.gradle" > settings.gradle

create_commit "d4e5f6g" "Add basic configuration" "Diana" 58 "main"
echo "application.properties" > application.properties

# Feature branch: user authentication
git checkout -b feature/auth
create_commit "e5f6g7h" "Start user auth feature" "Alice" 57 "feature/auth"
mkdir -p src/main/java/auth
echo "Auth module started" > src/main/java/auth/AuthService.java

create_commit "f6g7h8i" "Add login form" "Alice" 56 "feature/auth"
echo "Login form HTML" > login.html

create_commit "g7h8i9j" "Implement password hashing" "Alice" 55 "feature/auth"
echo "Password hashing logic" > src/main/java/auth/PasswordUtil.java

# Hotfix branch (from main)
git checkout main
git checkout -b hotfix/security
create_commit "h8i9j0k" "Critical security patch" "Bob" 56 "hotfix/security"
echo "Security patch applied" > security-patch.txt

create_commit "i9j0k1l" "Update dependencies" "Bob" 55 "hotfix/security"
echo "Dependencies updated" > dependencies.txt

# Merge hotfix back to main
create_merge "Merge security hotfix" "Bob" 54 "main" "hotfix/security"

# Continue with auth feature
git checkout feature/auth
create_commit "k1l2m3n" "Add session management" "Alice" 53 "feature/auth"
echo "Session management" > src/main/java/auth/SessionManager.java

create_commit "l2m3n4o" "Fix password validation" "Alice" 52 "feature/auth"
echo "Password validation fixed" > src/main/java/auth/PasswordValidator.java

# Feature branch: API development (from main after hotfix merge)
git checkout main
git checkout -b feature/api
create_commit "m3n4o5p" "Setup API routes" "Charlie" 53 "feature/api"
mkdir -p src/main/java/api
echo "API routes setup" > src/main/java/api/Routes.java

create_commit "n4o5p6q" "Add user endpoints" "Charlie" 52 "feature/api"
echo "User endpoints" > src/main/java/api/UserController.java

create_commit "o5p6q7r" "Implement data validation" "Charlie" 51 "feature/api"
echo "Data validation" > src/main/java/api/ValidationService.java

# Feature branch: UI improvements (from main)
git checkout main
git checkout -b feature/ui-redesign
create_commit "p6q7r8s" "Redesign navigation" "Diana" 51 "feature/ui-redesign"
mkdir -p src/main/resources/static/css
echo "Navigation styles" > src/main/resources/static/css/nav.css

create_commit "q7r8s9t" "Add responsive design" "Diana" 50 "feature/ui-redesign"
echo "Responsive CSS" > src/main/resources/static/css/responsive.css

create_commit "r8s9t0u" "Improve accessibility" "Diana" 49 "feature/ui-redesign"
echo "Accessibility improvements" > src/main/resources/static/css/accessibility.css

# More work on main
git checkout main
create_commit "s9t0u1v" "Update README with setup instructions" "Bob" 50 "main"
echo -e "\n## Setup Instructions\n1. Clone repository\n2. Run build" >> README.md

# Merge auth feature
create_merge "Merge feature/auth" "Alice" 49 "main" "feature/auth"

# Development branch
git checkout -b develop
create_commit "u1v2w3x" "Merge latest main" "Eve" 48 "develop"
echo "Development branch setup" > develop-notes.txt

# Continue API work
git checkout feature/api
create_commit "v2w3x4y" "Add error handling" "Charlie" 48 "feature/api"
echo "Error handling" > src/main/java/api/ErrorHandler.java

create_commit "w3x4y5z" "Implement rate limiting" "Charlie" 47 "feature/api"
echo "Rate limiting" > src/main/java/api/RateLimiter.java

# Merge API to develop
create_merge "Merge feature/api to develop" "Eve" 46 "develop" "feature/api"

# Testing branch
git checkout develop
git checkout -b feature/testing
create_commit "y5z6a7b" "Add unit tests" "Frank" 46 "feature/testing"
mkdir -p src/test/java
echo "Unit tests" > src/test/java/UnitTests.java

create_commit "z6a7b8c" "Add integration tests" "Frank" 45 "feature/testing"
echo "Integration tests" > src/test/java/IntegrationTests.java

create_commit "a7b8c9d" "Setup CI pipeline" "Frank" 44 "feature/testing"
echo "CI configuration" > .github/workflows/ci.yml

# Merge UI improvements to main
create_merge "Merge UI redesign" "Diana" 45 "main" "feature/ui-redesign"

# Bug fix branch
git checkout main
git checkout -b bugfix/login-redirect
create_commit "c9d0e1f" "Fix login redirect issue" "Grace" 44 "bugfix/login-redirect"
echo "Login redirect fix" > src/main/java/auth/LoginRedirectFix.java

create_commit "d0e1f2g" "Add error logging" "Grace" 43 "bugfix/login-redirect"
echo "Error logging" > src/main/java/logging/ErrorLogger.java

# Merge testing back to develop
create_merge "Merge testing framework" "Eve" 43 "develop" "feature/testing"

# Documentation branch
git checkout develop
git checkout -b feature/docs
create_commit "f2g3h4i" "Add API documentation" "Henry" 42 "feature/docs"
mkdir -p docs
echo "API Documentation" > docs/api.md

create_commit "g3h4i5j" "Update user guide" "Henry" 41 "feature/docs"
echo "User Guide" > docs/user-guide.md

# Merge bug fix to main
create_merge "Merge login fix" "Grace" 42 "main" "bugfix/login-redirect"

# Performance branch
git checkout main
git checkout -b feature/performance
create_commit "i5j6k7l" "Optimize database queries" "Ian" 41 "feature/performance"
echo "Database optimizations" > src/main/java/db/QueryOptimizer.java

create_commit "j6k7l8m" "Add caching layer" "Ian" 40 "feature/performance"
echo "Caching implementation" > src/main/java/cache/CacheService.java

create_commit "k7l8m9n" "Implement lazy loading" "Ian" 39 "feature/performance"
echo "Lazy loading" > src/main/java/lazy/LazyLoader.java

# Merge docs to develop
create_merge "Merge documentation" "Eve" 40 "develop" "feature/docs"

# Release preparation
git checkout develop
git checkout -b release/v1.0
create_commit "m9n0o1p" "Prepare v1.0 release" "Alice" 38 "release/v1.0"
echo "version=1.0.0" > version.properties

create_commit "n0o1p2q" "Update version numbers" "Alice" 37 "release/v1.0"
echo "Version numbers updated" >> version.properties

create_commit "o1p2q3r" "Final testing and bug fixes" "Alice" 36 "release/v1.0"
echo "Release ready" >> version.properties

# Merge performance improvements to main
create_merge "Merge performance optimizations" "Ian" 37 "main" "feature/performance"

# Merge release to main
create_merge "Release v1.0" "Alice" 35 "main" "release/v1.0"

# Post-release hotfix
git checkout main
git checkout -b hotfix/v1.0.1
create_commit "r4s5t6u" "Hotfix: memory leak" "Bob" 34 "hotfix/v1.0.1"
echo "Memory leak fix" > src/main/java/memory/MemoryFix.java

create_commit "s5t6u7v" "Update changelog" "Bob" 33 "hotfix/v1.0.1"
echo "# Changelog\n## v1.0.1\n- Fixed memory leak" > CHANGELOG.md

# Merge hotfix
create_merge "Release v1.0.1" "Bob" 32 "main" "hotfix/v1.0.1"

# Latest commits
git checkout main
create_commit "u7v8w9x" "Start planning v2.0" "Alice" 31 "main"
echo "v2.0 planning started" > v2-planning.txt

create_commit "v8w9x0y" "Start planning v3.0" "Alice" 30 "main"
echo "v3.0 planning started" > v3-planning.txt

echo ""
echo "Repository created successfully!"
echo "Location: $(pwd)"
echo ""
echo "To explore the repository:"
echo "  cd $REPO_NAME"
echo "  git log --oneline --graph --all"
echo "  git branch -a"
echo ""
echo "Available branches:"
git branch -a | grep -v "HEAD"
echo ""
echo "Recent commits:"
git log --oneline -10