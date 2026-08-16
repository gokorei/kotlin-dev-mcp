# Security Policy

## Reporting a Vulnerability

Please report suspected security vulnerabilities **privately** using GitHub's
private vulnerability reporting (**Security → Report a vulnerability** on this
repository). Do **not** open a public issue.

Please include:

- Affected version and how the server was built/run
- Steps to reproduce
- A minimal proof-of-concept, with any secrets redacted

You should receive an acknowledgement within 3 business days.

## Scope & safe-use notes

- `kotlin_run_snippet` **compiles and executes arbitrary Kotlin** in a
  subprocess. Treat any use of this server on untrusted input as equivalent to
  executing that input.
- The embedded compiler, subprocess execution, and stdio transport are the main
  attack surface for this project.
