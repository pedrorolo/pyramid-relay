---
name: git-commit
description: Stage all changes and commit them with a descriptive message. Use when the user asks to commit changes, save work, or create a git commit.
---

# Git Commit

Stage all changes and commit them with a descriptive message.

## Steps

### 1. Check status

```bash
git status
```

### 2. Review changes

```bash
git diff
```

### 3. Stage all changes

```bash
git add .
```

### 4. Commit with descriptive message

```bash
git commit -am "description of changes"
```

## Message Format

Use a concise, descriptive commit message summarizing the changes. Format:

```
<imperative verb> <what changed>
```

Examples:
- "Remove WifiDirectService and add opencode skills"
- "Update BLE timeouts for poor connection resilience"
- "Add notification icon and adjust launcher logo"

## Notes

- Only commit when explicitly requested by the user
- Review the diff before committing to ensure all intended changes are included
- Do not commit files that contain secrets or sensitive information
