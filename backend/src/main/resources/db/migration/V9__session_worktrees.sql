ALTER TABLE sessions ADD COLUMN worktree_branch text;
ALTER TABLE sessions ADD COLUMN worktree_base_branch text;
ALTER TABLE sessions ADD COLUMN worktree_state text
    CHECK (worktree_state IN ('active', 'merged', 'discarded'));
